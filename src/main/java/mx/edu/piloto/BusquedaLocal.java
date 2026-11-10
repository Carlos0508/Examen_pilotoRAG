package mx.edu.piloto;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Busqueda exhaustiva por coseno en un indice pequeno; solo HTTP de loopback. */
public final class BusquedaLocal {
 static final String MODEL="bge-m3:latest";
 static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
 static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
 static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p,StandardCharsets.UTF_8)).getAsJsonObject();}
 static JsonObject api(String endpoint,JsonObject body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:11434/api/"+endpoint)).timeout(Duration.ofMinutes(3));
  if(body==null)b.GET();else b.header("Content-Type","application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body),StandardCharsets.UTF_8));
  var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  if(r.statusCode()!=200)throw new IllegalStateException("Ollama HTTP "+r.statusCode()+": "+r.body());
  return JsonParser.parseString(r.body()).getAsJsonObject();
 }
 static String digest()throws Exception{
  for(var m:api("tags",null).getAsJsonArray("models")){
   var o=m.getAsJsonObject();if(o.get("name").getAsString().equals(MODEL))return o.get("digest").getAsString();
  }
  throw new IllegalStateException("Falta "+MODEL+". Ejecutar: ollama pull bge-m3");
 }
 static double[] normalize(JsonArray array){
  if(array.size()!=1024)throw new IllegalArgumentException("Dimension inesperada: "+array.size());
  double[] v=new double[array.size()];double n=0;
  for(int i=0;i<v.length;i++){v[i]=array.get(i).getAsDouble();if(!Double.isFinite(v[i]))throw new IllegalArgumentException("Vector no finito");n+=v[i]*v[i];}
  if(!Double.isFinite(n)||n<=0)throw new IllegalArgumentException("Norma invalida");
  n=Math.sqrt(n);for(int i=0;i<v.length;i++)v[i]/=n;return v;
 }
 static List<double[]> embed(List<String> texts)throws Exception{
  JsonObject request=new JsonObject();request.addProperty("model",MODEL);request.add("input",JSON.toJsonTree(texts));
  request.addProperty("truncate",false);request.addProperty("keep_alive","2m");
  JsonArray arr=api("embed",request).getAsJsonArray("embeddings");
  if(arr.size()!=texts.size())throw new IllegalArgumentException("Cantidad de embeddings incorrecta");
  List<double[]> vectors=new ArrayList<>();for(var a:arr)vectors.add(normalize(a.getAsJsonArray()));return vectors;
 }
 static void write(Path p,JsonObject o)throws Exception{
  Files.writeString(p,JSON.toJson(o)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
 }
 static void index(Path chunks,Path approval,Path out)throws Exception{
  if(Files.exists(out))throw new IllegalArgumentException("La salida ya existe");
  chunks=chunks.toRealPath();approval=approval.toRealPath();JsonObject approved=read(approval);
  if(!approved.get("estado").getAsString().equals("FRAGMENTACION_CONFIRMADA_POR_USUARIO")||!approved.get("fragmentos_sha256").getAsString().equals(OcrPagina.hash(chunks)))
   throw new IllegalArgumentException("Fragmentos sin aprobacion coincidente");
  Set<String> expected=new HashSet<>();for(var id:approved.getAsJsonArray("fragmentos_ids"))expected.add(id.getAsString());
  List<JsonObject> documents=new ArrayList<>();List<String> texts=new ArrayList<>();Set<String> seen=new HashSet<>();
  for(String line:Files.readAllLines(chunks,StandardCharsets.UTF_8)){
   if(line.isBlank())continue;JsonObject c=JsonParser.parseString(line).getAsJsonObject();String id=c.get("id").getAsString();
   if(!seen.add(id)||!expected.contains(id))throw new IllegalArgumentException("ID duplicado o no aprobado");
   Path source=Path.of(c.get("fuente_texto").getAsString());
   if(!OcrPagina.hash(source).equals(c.get("fuente_texto_sha256").getAsString()))throw new IllegalArgumentException("Transcripcion alterada");
   if(!OcrPagina.hash(Path.of(c.get("fuente_pdf").getAsString())).equals(c.get("fuente_pdf_sha256").getAsString()))throw new IllegalArgumentException("PDF alterado");
   String text=c.get("texto").getAsString();String original=Files.readString(source,StandardCharsets.UTF_8);
   if(!original.substring(c.get("inicio_utf16").getAsInt(),c.get("fin_utf16_exclusivo").getAsInt()).equals(text))throw new IllegalArgumentException("Fragmento no literal");
   documents.add(c);texts.add(text);
  }
  if(documents.isEmpty()||!seen.equals(expected))throw new IllegalArgumentException("Aprobacion incompleta");
  String revision=digest();List<double[]> vectors=embed(texts);
  if(!revision.equals(digest()))throw new IllegalStateException("Cambio de modelo durante ingesta");
  JsonArray entries=new JsonArray();for(int i=0;i<documents.size();i++){JsonObject entry=new JsonObject();entry.add("fragmento",documents.get(i));entry.add("vector",JSON.toJsonTree(vectors.get(i)));entries.add(entry);}
  JsonObject result=new JsonObject();result.addProperty("formato","indice-local-v1");result.addProperty("modelo",MODEL);result.addProperty("modelo_digest",revision);result.addProperty("dimensiones",1024);
  result.addProperty("metrica","coseno");result.addProperty("normalizacion","L2");result.addProperty("entrada_embedding","texto_literal_sin_metadatos");result.addProperty("truncate",false);
  result.addProperty("fragmentos_sha256",OcrPagina.hash(chunks));result.addProperty("aprobacion_sha256",OcrPagina.hash(approval));result.add("entradas",entries);
  Files.createDirectories(out);write(out.resolve("indice.json"),result);
  System.out.println("Indice local creado: "+documents.size()+" vectores de 1024 dimensiones. "+out);
 }
 static void search(Path index,String question,Path output)throws Exception{ search(index,question,output,true); }
 static void search(Path index,String question,Path output,boolean showResults)throws Exception{
  if(question.isBlank()||Files.exists(output))throw new IllegalArgumentException("Consulta vacia o salida existente");
  JsonObject store=read(index);
  if(!store.get("formato").getAsString().equals("indice-local-v1")||!store.get("modelo").getAsString().equals(MODEL))throw new IllegalArgumentException("Formato o modelo incompatible");
  String revision=digest();if(!revision.equals(store.get("modelo_digest").getAsString()))throw new IllegalArgumentException("El modelo cambio: reconstruir indice");
  double[] query=embed(List.of(question)).getFirst();
  if(!revision.equals(digest()))throw new IllegalStateException("Cambio de modelo durante consulta");
  List<JsonObject> ranked=new ArrayList<>();
  for(var element:store.getAsJsonArray("entradas")){
   var entry=element.getAsJsonObject();double[] v=normalize(entry.getAsJsonArray("vector"));double score=0;
   for(int i=0;i<query.length;i++)score+=query[i]*v[i];
   JsonObject hit=new JsonObject();hit.addProperty("similitud_coseno",score);hit.add("fragmento",entry.get("fragmento"));ranked.add(hit);
  }
  ranked.sort(Comparator.comparingDouble((JsonObject a)->a.get("similitud_coseno").getAsDouble()).reversed());
  JsonObject result=new JsonObject();result.addProperty("consulta",question);result.addProperty("modelo_digest",revision);result.addProperty("indice_sha256",OcrPagina.hash(index));
  result.addProperty("advertencia","Similitud no es probabilidad ni prueba de que exista respuesta. Sin generacion ni umbral de abstencion calibrado.");
  result.add("resultados",JSON.toJsonTree(ranked));write(output,result);
  for(int i=0;showResults && i<Math.min(3,ranked.size());i++){
   var hit=ranked.get(i);var c=hit.getAsJsonObject("fragmento");
   System.out.printf(Locale.ROOT,"%d. parrafo %d | similitud %.4f | circular %s | pagina %d%n%s%n%n",i+1,c.get("orden").getAsInt(),hit.get("similitud_coseno").getAsDouble(),c.get("clave").getAsString(),c.get("pagina").getAsInt(),c.get("texto").getAsString());
  }
 }
 public static void main(String[] args)throws Exception{
  if(args.length==4&&args[0].equals("indexar"))index(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));
  else if(args.length==4&&args[0].equals("buscar"))search(Path.of(args[1]),args[2],Path.of(args[3]));
  else throw new IllegalArgumentException("Uso: indexar <fragmentos.jsonl> <aprobacion.json> <salida-nueva> | buscar <indice.json> <pregunta> <resultado-nuevo.json>");
 }
}

