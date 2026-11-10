package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Qdrant REST local; corpus JSON permanece como fuente documental verificable. */
public final class QdrantLocal {
 static final String BASE="http://127.0.0.1:6333";
 static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 static JsonObject api(String method,String path,JsonElement body)throws Exception{
  var b=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(90));
  if(body==null)b.method(method,HttpRequest.BodyPublishers.noBody());else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(CorpusMultidocumento.JSON.toJson(body),StandardCharsets.UTF_8));
  var r=HTTP.send(b.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  if(r.statusCode()<200||r.statusCode()>=300)throw new IllegalStateException("Qdrant HTTP "+r.statusCode()+" en "+path+": "+r.body());
  return JsonParser.parseString(r.body()).getAsJsonObject();
 }
 static Path root(Path index){for(Path p=index.toAbsolutePath().getParent();p!=null;p=p.getParent())if(Files.isRegularFile(p.resolve("pom.xml"))&&Files.isDirectory(p.resolve("interfaz")))return p;return null;}
 static boolean enabled(Path root)throws Exception{
  if(root==null||!Files.exists(root.resolve("qdrant-activo.json")))return false;
  var c=CorpusMultidocumento.read(root.resolve("qdrant-activo.json"));if(!c.get("backend").getAsString().equals("QDRANT_LOCAL"))throw new IllegalArgumentException("Backend local desconocido");return true;
 }
 static String collection(String hash){if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Huella inválida");return "circulares_"+hash.substring(0,20);}
 static String pointId(String hash,String id){return UUID.nameUUIDFromBytes(("piloto-circulares:"+hash+":"+id).getBytes(StandardCharsets.UTF_8)).toString();}
 static JsonObject payload(String hash,String digest,JsonObject c){var p=new JsonObject();p.addProperty("indice_sha256",hash);p.addProperty("modelo_digest",digest);p.add("fragmento_id",c.get("id"));p.add("documento_id",c.get("documento_id"));p.add("fragmento",c);return p;}
 static List<JsonObject> scroll(String name)throws Exception{
  List<JsonObject> rows=new ArrayList<>();JsonElement offset=null;
  do{var q=new JsonObject();q.addProperty("limit",128);q.addProperty("with_payload",true);q.addProperty("with_vector",true);if(offset!=null)q.add("offset",offset);
   var result=api("POST","/collections/"+name+"/points/scroll",q).getAsJsonObject("result");for(var e:result.getAsJsonArray("points"))rows.add(e.getAsJsonObject());offset=result.get("next_page_offset");
  }while(offset!=null&&!offset.isJsonNull());return rows;
 }
 static JsonObject verify(Path index)throws Exception{
  String hash=OcrPagina.hash(index),name=collection(hash);var store=CorpusMultidocumento.read(index);var info=api("GET","/collections/"+name,null).getAsJsonObject("result");var vectors=info.getAsJsonObject("config").getAsJsonObject("params").getAsJsonObject("vectors");
  if(vectors.get("size").getAsInt()!=1024||!vectors.get("distance").getAsString().equals("Cosine"))throw new IllegalArgumentException("Colección incompatible");
  Map<String,JsonObject> expected=new HashMap<>();for(var e:store.getAsJsonArray("entradas")){var row=e.getAsJsonObject();expected.put(pointId(hash,row.getAsJsonObject("fragmento").get("id").getAsString()),row);}
  Set<String> seen=new HashSet<>();double maxError=0;
  for(var row:scroll(name)){String id=row.get("id").getAsString();var original=expected.get(id);if(original==null||!seen.add(id))throw new IllegalArgumentException("Punto desconocido/duplicado");
   if(!row.getAsJsonObject("payload").equals(payload(hash,store.get("modelo_digest").getAsString(),original.getAsJsonObject("fragmento"))))throw new IllegalArgumentException("Payload alterado: "+id);
   var v=row.getAsJsonArray("vector");var source=original.getAsJsonArray("vector");if(v.size()!=1024)throw new IllegalArgumentException("Dimensión inesperada");
   for(int i=0;i<1024;i++){double error=Math.abs(v.get(i).getAsDouble()-source.get(i).getAsDouble());if(!Double.isFinite(error)||error>0.000005)throw new IllegalArgumentException("Vector no coincide con fuente");maxError=Math.max(maxError,error);}
  }
  if(!seen.equals(expected.keySet()))throw new IllegalArgumentException("Faltan puntos en Qdrant");
  var record=new JsonObject();record.addProperty("backend","QDRANT_LOCAL");record.addProperty("servidor",BASE);record.addProperty("coleccion",name);record.addProperty("indice",index.toRealPath().toString());record.addProperty("indice_sha256",hash);record.add("modelo_digest",store.get("modelo_digest"));record.addProperty("puntos_verificados",seen.size());record.addProperty("dimensiones",1024);record.addProperty("metrica","Cosine");record.addProperty("max_error_vector_float32",maxError);return record;
 }
 static JsonObject importIndex(Path root,Path index)throws Exception{
  var store=CorpusMultidocumento.read(index);CorpusMultidocumento.verifyIndex(store);String hash=OcrPagina.hash(index),name=collection(hash),digest=store.get("modelo_digest").getAsString();
  boolean exists=false;for(var e:api("GET","/collections",null).getAsJsonObject("result").getAsJsonArray("collections"))if(e.getAsJsonObject().get("name").getAsString().equals(name))exists=true;
  if(!exists){var cfg=new JsonObject();var v=new JsonObject();v.addProperty("size",1024);v.addProperty("distance","Cosine");cfg.add("vectors",v);cfg.addProperty("on_disk_payload",true);api("PUT","/collections/"+name,cfg);}
  Set<String> ids=new HashSet<>();for(var e:store.getAsJsonArray("entradas"))ids.add(pointId(hash,e.getAsJsonObject().getAsJsonObject("fragmento").get("id").getAsString()));
  for(var p:scroll(name))if(!ids.contains(p.get("id").getAsString())||!p.getAsJsonObject("payload").get("indice_sha256").getAsString().equals(hash))throw new IllegalArgumentException("Colección preexistente ajena; no se sobrescribe");
  var entries=store.getAsJsonArray("entradas");for(int start=0;start<entries.size();start+=64){var points=new JsonArray();for(int i=start;i<Math.min(start+64,entries.size());i++){var e=entries.get(i).getAsJsonObject();var c=e.getAsJsonObject("fragmento");var p=new JsonObject();p.addProperty("id",pointId(hash,c.get("id").getAsString()));p.add("vector",e.get("vector"));p.add("payload",payload(hash,digest,c));points.add(p);}var body=new JsonObject();body.add("points",points);api("PUT","/collections/"+name+"/points?wait=true",body);}
  var record=verify(index);Path dir=root.resolve("qdrant-indices");Files.createDirectories(dir);RegistroAcervo.atomic(dir.resolve(hash+".json"),record);return record;
 }
 static List<JsonObject> dense(Path index,JsonObject store,List<JsonObject> candidates,double[] query)throws Exception{
  String hash=OcrPagina.hash(index);Path root=root(index);var record=CorpusMultidocumento.read(root.resolve("qdrant-indices").resolve(hash+".json"));if(!record.get("indice_sha256").getAsString().equals(hash)||!record.get("modelo_digest").equals(store.get("modelo_digest")))throw new IllegalArgumentException("Qdrant sin versión documental coincidente");
  Map<String,JsonObject> expected=new HashMap<>();var ids=new JsonArray();for(var e:candidates){var c=e.getAsJsonObject("fragmento");String id=pointId(hash,c.get("id").getAsString());expected.put(id,c);ids.add(id);}
  var body=new JsonObject();body.add("query",CorpusMultidocumento.JSON.toJsonTree(query));body.addProperty("limit",candidates.size());body.addProperty("with_payload",true);var params=new JsonObject();params.addProperty("exact",true);body.add("params",params);var must=new JsonArray();var scope=new JsonObject();scope.add("has_id",ids);must.add(scope);var filter=new JsonObject();filter.add("must",must);body.add("filter",filter);
  List<JsonObject> ranked=new ArrayList<>();Set<String> seen=new HashSet<>();for(var e:api("POST","/collections/"+collection(hash)+"/points/query",body).getAsJsonObject("result").getAsJsonArray("points")){
   var p=e.getAsJsonObject();String id=p.get("id").getAsString();var original=expected.get(id);if(original==null||!seen.add(id)||!p.getAsJsonObject("payload").equals(payload(hash,store.get("modelo_digest").getAsString(),original)))throw new IllegalArgumentException("Resultado ajeno o alterado en Qdrant");
   double score=p.get("score").getAsDouble();if(!Double.isFinite(score))throw new IllegalArgumentException("Puntuación no finita");var hit=new JsonObject();hit.addProperty("similitud_coseno",score);hit.add("fragmento",original);ranked.add(hit);
  }
  if(seen.size()!=expected.size())throw new IllegalArgumentException("Qdrant no devolvió todos los candidatos del piloto");return ranked;
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=2||!Set.of("importar","verificar","activar").contains(args[0]))throw new IllegalArgumentException("Uso: importar|verificar|activar <raíz-piloto>");Path root=Path.of(args[1]).toRealPath(),index=RegistroAcervo.active(root);var record=args[0].equals("verificar")?verify(index):importIndex(root,index);
  if(args[0].equals("activar")){var flag=new JsonObject();flag.addProperty("backend","QDRANT_LOCAL");flag.addProperty("formato","activacion-qdrant-local-v1");RegistroAcervo.atomic(root.resolve("qdrant-activo.json"),flag);}else if(!Set.of("importar","verificar").contains(args[0]))throw new IllegalArgumentException("Acción desconocida");
  System.out.println(CorpusMultidocumento.JSON.toJson(record));
 }
}
