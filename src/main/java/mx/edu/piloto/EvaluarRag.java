package mx.edu.piloto;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Evaluacion de desarrollo reproducible: no certifica correccion semantica. */
public final class EvaluarRag {
 static Set<String> ids(JsonArray values){
  Set<String> ids=new LinkedHashSet<>();for(var v:values)if(!ids.add(v.getAsString()))throw new IllegalArgumentException("ID repetido");return ids;
 }
 static JsonObject retrieval(List<String> ranking,Set<String> relevant,int k){
  if(k<1||relevant.isEmpty()||new HashSet<>(ranking).size()!=ranking.size())throw new IllegalArgumentException("Entrada metrica invalida");
  int found=0,first=0;for(int i=0;i<Math.min(k,ranking.size());i++)if(relevant.contains(ranking.get(i))){found++;if(first==0)first=i+1;}
  JsonObject m=new JsonObject();m.addProperty("precision",found/(double)k);m.addProperty("recall",found/(double)relevant.size());m.addProperty("hit_rate",found>0?1:0);m.addProperty("mrr",first==0?0:1.0/first);m.addProperty("cobertura_completa",found==relevant.size()?1:0);return m;
 }
 static List<String> ranking(JsonObject r){
  List<String> ids=new ArrayList<>();for(var h:r.getAsJsonArray("resultados"))ids.add(h.getAsJsonObject().getAsJsonObject("fragmento").get("id").getAsString());return ids;
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("Uso: EvaluarRag <preguntas-confirmadas.json> <indice.json> <salida-nueva>");
  Path dataset=Path.of(args[0]).toRealPath(),index=Path.of(args[1]).toRealPath(),out=Path.of(args[2]).toAbsolutePath().normalize();
  if(Files.exists(out))throw new IllegalArgumentException("La salida ya existe");
  JsonObject questions=BusquedaLocal.read(dataset),approval=BusquedaLocal.read(dataset.getParent().resolve("aprobacion-preguntas.json")),store=BusquedaLocal.read(index);
  if(!questions.get("estado_etiquetas").getAsString().equals("CONFIRMADAS_POR_USUARIO")||!approval.get("estado").getAsString().equals("ETIQUETAS_CONFIRMADAS_POR_USUARIO")||!OcrPagina.hash(dataset).equals(approval.get("preguntas_sha256").getAsString()))throw new IllegalArgumentException("Dataset no coincide con confirmacion humana");
  if(!questions.get("fragmentos_sha256").equals(store.get("fragmentos_sha256")))throw new IllegalArgumentException("Corpus de evaluacion distinto al indice");
  Map<String,JsonObject> corpus=new HashMap<>();for(var e:store.getAsJsonArray("entradas")){JsonObject c=e.getAsJsonObject().getAsJsonObject("fragmento");if(corpus.put(c.get("id").getAsString(),c)!=null)throw new IllegalArgumentException("Indice duplicado");}
  JsonArray cases=questions.getAsJsonArray("casos");if(cases.isEmpty())throw new IllegalArgumentException("Dataset vacio");Set<String> caseIds=new HashSet<>();
  for(var entry:cases){var c=entry.getAsJsonObject();String id=c.get("id").getAsString();if(!id.matches("[A-Za-z0-9_-]+")||!caseIds.add(id))throw new IllegalArgumentException("ID de pregunta invalido");Set<String> relevant=ids(c.getAsJsonArray("fragmentos_relevantes")),needed=ids(c.getAsJsonArray("evidencia_completa_requerida"));
   if(!corpus.keySet().containsAll(relevant)||!relevant.containsAll(needed)||c.get("estado_esperado").getAsString().equals("EVIDENCIA_ENCONTRADA")==relevant.isEmpty())throw new IllegalArgumentException("Etiquetas inconsistentes");
  }
  Files.createDirectories(out);
  // Congelar entradas e identificadores de implementacion antes de cualquier consulta.
  JsonObject frozen=new JsonObject();frozen.addProperty("dataset_sha256",OcrPagina.hash(dataset));frozen.addProperty("indice_sha256",OcrPagina.hash(index));frozen.addProperty("modelo_embeddings_digest",store.get("modelo_digest").getAsString());frozen.addProperty("modelo_generacion_digest",RagLocal.generatorDigest());frozen.addProperty("k_recuperacion",3);frozen.addProperty("politica_contexto",RagLocal.CONTEXT_POLICY);frozen.addProperty("contexto_max_caracteres_utf16",RagLocal.MAX_CONTEXT_CHARS);
  JsonObject code=new JsonObject();Path sources=index.getParent().getParent().resolve("src/main/java/mx/edu/piloto");
  for(String f:List.of("RagLocal.java","BusquedaLocal.java","EvaluarRag.java","RespuestaPorPartes.java","ControlEvidencia.java"))code.addProperty(f,OcrPagina.hash(sources.resolve(f)));frozen.add("fuentes_sha256",code);
  BusquedaLocal.write(out.resolve("entradas-congeladas.json"),frozen);
  JsonArray detail=new JsonArray();double[][] sums=new double[2][5];int positives=0,negatives=0,blocked=0,correctStates=0,abstain=0,falseAbstain=0,full=0,emitted=0,literal=0,selectedCount=0,selectedRelevant=0;JsonArray errors=new JsonArray();List<Double> durations=new ArrayList<>();
  for(var entry:cases){
   JsonObject c=entry.getAsJsonObject();String id=c.get("id").getAsString();Set<String> relevant=ids(c.getAsJsonArray("fragmentos_relevantes")),needed=ids(c.getAsJsonArray("evidencia_completa_requerida"));boolean positive=!relevant.isEmpty();if(positive)positives++;else negatives++;
   JsonObject d=new JsonObject();d.addProperty("id",id);d.addProperty("pregunta",c.get("pregunta").getAsString());d.addProperty("estado_esperado",c.get("estado_esperado").getAsString());long begin=System.nanoTime();Path folder=out.resolve(id);
   try{
    RagLocal.main(new String[]{index.toString(),c.get("pregunta").getAsString(),folder.toString()});
    JsonObject r=BusquedaLocal.read(folder.resolve("respuesta.json")),retrieved=BusquedaLocal.read(folder.resolve("recuperacion.json"));List<String> ranking=ranking(retrieved);
    if(!retrieved.get("indice_sha256").equals(frozen.get("indice_sha256"))||!r.get("modelo_digest").equals(frozen.get("modelo_generacion_digest"))||!retrieved.get("modelo_digest").equals(frozen.get("modelo_embeddings_digest")))throw new IllegalStateException("Cambio de configuracion durante evaluacion");
    String state=r.get("estado").getAsString();d.addProperty("estado_observado",state);d.add("ranking_ids",BusquedaLocal.JSON.toJsonTree(ranking));
    JsonObject context=BusquedaLocal.read(folder.resolve("contexto.json"));Set<String> contextIds=new HashSet<>();for(var fragment:context.getAsJsonArray("fragmentos"))contextIds.add(fragment.getAsJsonObject().get("id").getAsString());d.add("contexto_ids",BusquedaLocal.JSON.toJsonTree(contextIds));d.addProperty("contexto_cubre_evidencia_requerida",positive&&contextIds.containsAll(needed));
    if(positive){for(int slot=0;slot<2;slot++){int k=slot==0?1:3;JsonObject m=retrieval(ranking,relevant,k);d.add("retrieval_at_"+k,m);String[] keys={"precision","recall","hit_rate","mrr","cobertura_completa"};for(int j=0;j<5;j++)sums[slot][j]+=m.get(keys[j]).getAsDouble();}}
    if(state.equals(c.get("estado_esperado").getAsString()))correctStates++;
    if(!positive&&state.equals("SIN_EVIDENCIA"))abstain++;
    if(positive&&state.equals("SIN_EVIDENCIA"))falseAbstain++;
    if(state.equals("RESPUESTA_BLOQUEADA"))blocked++;
    d.add("partes",r.get("partes"));
    Set<String> selected=new HashSet<>();boolean allLiteral=true;
    for(var citation:r.getAsJsonArray("citas")){
     var cite=citation.getAsJsonObject();String selectedId=cite.get("id").getAsString();selected.add(selectedId);emitted++;
     boolean exact=corpus.containsKey(selectedId)&&cite.equals(corpus.get(selectedId));if(exact)literal++;else allLiteral=false;
     selectedCount++;if(relevant.contains(selectedId))selectedRelevant++;
    }
    boolean sufficient=positive&&state.equals("EVIDENCIA_ENCONTRADA")&&selected.containsAll(needed)&&allLiteral;if(sufficient)full++;
    d.add("citas_ids",BusquedaLocal.JSON.toJsonTree(selected));d.addProperty("evidencias_requeridas_cubiertas",sufficient);d.addProperty("citas_literales_y_metadatos_coincidentes",allLiteral);
   }catch(Exception e){d.addProperty("error",e.toString());errors.add(id+": "+e);}
   double seconds=(System.nanoTime()-begin)/1e9;durations.add(seconds);d.addProperty("duracion_segundos",seconds);detail.add(d);
   BusquedaLocal.write(out.resolve(id+"-medicion.json"),d);System.out.println("EVALUADO "+id+" ("+String.format(Locale.ROOT,"%.1f",seconds)+" s)");
  }
  JsonObject report=new JsonObject();report.addProperty("tipo","EVALUACION_DESARROLLO_UNA_CIRCULAR");report.addProperty("preguntas",cases.size());report.addProperty("respondibles",positives);report.addProperty("sin_respuesta",negatives);report.add("entradas",frozen);report.add("errores",errors);report.add("casos",detail);
  JsonObject metrics=new JsonObject();String[] keys={"precision","recall","hit_rate","mrr","cobertura_completa"};for(int slot=0;slot<2;slot++){JsonObject m=new JsonObject();m.addProperty("denominador_preguntas",positives);for(int j=0;j<5;j++)if(positives>0)m.addProperty(keys[j],sums[slot][j]/positives);metrics.add("at_"+(slot==0?1:3),m);}report.add("retrieval_promedios_macro",metrics);
  JsonObject generation=new JsonObject();generation.addProperty("estados_esperados_coincidentes",correctStates);generation.addProperty("abstenciones_correctas_sobre_negativas",abstain);generation.addProperty("denominador_negativas",negatives);generation.addProperty("abstenciones_innecesarias_sobre_positivas",falseAbstain);generation.addProperty("denominador_positivas",positives);generation.addProperty("positivas_con_evidencia_completa_y_literal",full);generation.addProperty("respuestas_bloqueadas",blocked);generation.addProperty("citas_emitidas",emitted);generation.addProperty("citas_literales_y_metadatos_coincidentes",literal);if(selectedCount>0)generation.addProperty("precision_citas_micro_segun_etiquetas",selectedRelevant/(double)selectedCount);report.add("respuesta_extractiva",generation);
  report.addProperty("limitaciones","Una circular y preguntas de desarrollo, algunas ya conocidas. No es prueba independiente. Evidencia completa mide cobertura de IDs etiquetados y literalidad, no un juicio semantico independiente. No se calculan IC inferenciales con una sola unidad documental. Errores de ejecucion se conservan y no se omiten del denominador.");
  BusquedaLocal.write(out.resolve("resumen.json"),report);System.out.println("RESUMEN: "+out.resolve("resumen.json"));
 }
}
