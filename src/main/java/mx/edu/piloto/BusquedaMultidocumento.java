package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Recuperacion hibrida y filtro exacto de claves; no confunde clave de anexo con clave propia. */
public final class BusquedaMultidocumento {
 static Set<String> keys(String question){return ClavesDocumentales.canonicas(question);}
 static boolean matches(JsonObject c,Set<String> keys){if(keys.isEmpty())return true;for(String name:List.of("clave","clave_padre"))if(c.has(name)&&!c.get(name).isJsonNull()&&keys.contains(c.get(name).getAsString()))return true;return false;}
 record SourceScope(boolean requested,Set<String> hashes,Set<String> names){}
 static SourceScope sourceScope(JsonObject index,String question)throws Exception{
  Set<String> names=new LinkedHashSet<>();var matcher=Pattern.compile("(?i)\\bcircular\\s*(?:\\(\\s*[0-9]+\\s*\\))?\\s*\\.pdf\\b").matcher(question);while(matcher.find())names.add(matcher.group().replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
  var quoted=Pattern.compile("(?i)archivo\\s+[\"“]([^\"”]+\\.pdf)[\"”]").matcher(question);while(quoted.find())names.add(quoted.group(1).replaceAll("\\s+", "").toLowerCase(Locale.ROOT));
  if(names.isEmpty())return new SourceScope(false,Set.of(),names);
  Set<String> hashes=new HashSet<>();var manifest=CorpusMultidocumento.read(Path.of(index.get("manifiesto").getAsString()));
  if(manifest.has("automatico")){Path manifestPath=Path.of(index.get("manifiesto").getAsString());Path cfgPath=CorpusMultidocumento.verified(manifestPath.getParent(),manifest.getAsJsonObject("automatico"));var cfg=CorpusMultidocumento.read(cfgPath);var catalog=CorpusMultidocumento.read(CorpusMultidocumento.verified(cfgPath.getParent(),cfg.getAsJsonObject("catalogo")));for(var e:catalog.getAsJsonArray("fuentes")){var f=e.getAsJsonObject();for(var n:f.getAsJsonArray("archivos_equivalentes"))if(names.contains(n.getAsString().replaceAll("\\s+", "").toLowerCase(Locale.ROOT)))hashes.add(f.get("sha256").getAsString());}}
  for(var f:RegistroAcervo.additions(index))if(names.contains(f.get("archivo").getAsString().replaceAll("\\s+", "").toLowerCase(Locale.ROOT)))hashes.add(f.getAsJsonObject("pdf").get("sha256").getAsString());
  return new SourceScope(true,hashes,names);
 }
 static JsonArray identityCollisions(List<JsonObject> candidates,Set<String> keys){
  Map<String,Set<String>> grouped=new TreeMap<>();for(var e:candidates){var c=e.getAsJsonObject("fragmento");for(String field:List.of("clave","clave_padre"))if(c.has(field)&&!c.get(field).isJsonNull()){String key=c.get(field).getAsString();if(!keys.isEmpty()&&!keys.contains(key))continue;String pdf=c.has("fuente_pdf_sha256")?c.get("fuente_pdf_sha256").getAsString():c.get("documento_id").getAsString();grouped.computeIfAbsent(key,k->new HashSet<>()).add(pdf);}}
  JsonArray result=new JsonArray();for(var e:grouped.entrySet())if(e.getValue().size()>1){var row=new JsonObject();row.addProperty("clave",e.getKey());row.addProperty("fuentes_pdf_distintas",e.getValue().size());row.addProperty("estado","POSIBLE_COLISION_NO_FUSIONAR_IDENTIDADES");result.add(row);}return result;
 }
 static JsonObject search(Path path,String question,Path output)throws Exception{
  if(question.isBlank()||question.length()>1000||Files.exists(output))throw new IllegalArgumentException("Consulta o salida invalida");
  JsonObject index=CorpusMultidocumento.read(path);if(!index.get("formato").getAsString().equals("indice-multidocumento-v1"))throw new IllegalArgumentException("Formato incompatible");CorpusMultidocumento.verifyIndex(index);
  if(!BusquedaLocal.digest().equals(index.get("modelo_digest").getAsString()))throw new IllegalArgumentException("Cambio de embeddings: reconstruir indice");Set<String> explicitKeys=keys(question);
  SourceScope sources=sourceScope(index,question);
  List<JsonObject> candidates=new ArrayList<>();for(var e:index.getAsJsonArray("entradas")){var entry=e.getAsJsonObject();if(matches(entry.getAsJsonObject("fragmento"),explicitKeys)&&CorpusAutomatico.eligible(entry.getAsJsonObject("fragmento"),question)&&(!sources.requested()||(entry.getAsJsonObject("fragmento").has("fuente_pdf_sha256")&&sources.hashes().contains(entry.getAsJsonObject("fragmento").get("fuente_pdf_sha256").getAsString()))))candidates.add(entry);}
  List<JsonObject> ranked=new ArrayList<>();
  if(!candidates.isEmpty()){
   double[] query=BusquedaLocal.embed(List.of(question)).getFirst();if(!BusquedaLocal.digest().equals(index.get("modelo_digest").getAsString()))throw new IllegalArgumentException("Cambio de modelo durante consulta");
   if(QdrantLocal.enabled(QdrantLocal.root(path)))ranked.addAll(QdrantLocal.dense(path,index,candidates,query));
   else for(var e:candidates){double[] vector=BusquedaLocal.normalize(e.getAsJsonArray("vector"));double score=0;for(int i=0;i<query.length;i++)score+=query[i]*vector[i];JsonObject h=new JsonObject();h.addProperty("similitud_coseno",score);h.add("fragmento",e.get("fragmento"));ranked.add(h);}
  }
  ranked.sort(Comparator.comparingDouble((JsonObject h)->h.get("similitud_coseno").getAsDouble()).reversed());JsonObject hybrid=BusquedaHibrida.fuse(ranked,question);JsonObject result=new JsonObject();result.addProperty("consulta",question);result.addProperty("backend_almacenamiento",QdrantLocal.enabled(QdrantLocal.root(path))?"QDRANT_LOCAL":"INDICE_JSON");result.add("claves_explicitas",CorpusMultidocumento.JSON.toJsonTree(explicitKeys));result.add("archivos_solicitados",CorpusMultidocumento.JSON.toJsonTree(sources.names()));result.add("posibles_claves_repetidas",identityCollisions(candidates,explicitKeys));result.add("resultados",hybrid.remove("resultados"));result.add("recuperacion_hibrida",hybrid);result.addProperty("indice_sha256",OcrPagina.hash(path));result.addProperty("modelo_digest",index.get("modelo_digest").getAsString());result.addProperty("aviso","Similitud no demuestra existencia de respuesta. Fuentes parcialmente revisadas identificadas como ensayo.");BusquedaLocal.write(output,result);return result;
 }
 public static void main(String[] args)throws Exception{if(args.length!=3)throw new IllegalArgumentException("Uso: <indice> <pregunta> <resultado-nuevo.json>");var r=search(Path.of(args[0]),args[1],Path.of(args[2]));var hits=r.getAsJsonArray("resultados");for(int i=0;i<Math.min(5,hits.size());i++){var h=hits.get(i).getAsJsonObject();var c=h.getAsJsonObject("fragmento");System.out.println((i+1)+". "+c.get("clave_cita").getAsString()+" | pagina "+c.get("pagina")+" | "+c.get("texto").getAsString());}}
}
