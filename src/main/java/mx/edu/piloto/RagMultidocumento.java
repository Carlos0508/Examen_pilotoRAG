package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Consulta local de varios documentos con ampliacion acotada y exclusiones antes del modelo. */
public final class RagMultidocumento {
 static JsonObject context(JsonObject retrieval)throws Exception{
  List<JsonObject> ranked=new ArrayList<>();for(var e:retrieval.getAsJsonArray("resultados"))ranked.add(e.getAsJsonObject().getAsJsonObject("fragmento"));
  LinkedHashMap<String,JsonObject> selected=new LinkedHashMap<>();JsonArray omitted=new JsonArray(),anchors=new JsonArray();int chars=0;
  // Reservar candidatos de ambos canales: RRF no debe borrar el mejor resultado léxico.
  // Son candidatos, no evidencia suficiente. La validación de citas conserva su función.
  List<JsonObject> priority=new ArrayList<>();
  if(!ranked.isEmpty()&&ranked.getFirst().has("relaciones_contexto"))priority.add(ranked.getFirst());
  var listGroups=ContextoListas.groups(ranked,retrieval.has("consulta")?retrieval.get("consulta").getAsString():"");
  for(var group:listGroups)priority.addAll(group);
  for(String channel:List.of("rango_lexico","rango_denso"))for(var entry:retrieval.getAsJsonArray("resultados")){
   var hit=entry.getAsJsonObject();if(hit.has(channel)&&!hit.get(channel).isJsonNull()&&hit.get(channel).getAsInt()==1){
    var c=hit.getAsJsonObject("fragmento");priority.add(c);var trace=new JsonObject();trace.add("id",c.get("id"));trace.addProperty("canal",channel);anchors.add(trace);break;
   }
  }
  priority.addAll(ranked.subList(0,Math.min(6,ranked.size())));
  // Reservar antecedentes explícitos antes de gastar el presupuesto en semillas ajenas.
  List<JsonObject> dependenciesFirst=new ArrayList<>();
  for(var seed:priority){if(seed.has("relaciones_contexto")&&seed.has("unidad_id"))for(var edge:seed.getAsJsonArray("relaciones_contexto")){var r=edge.getAsJsonObject();if(r.get("destino").equals(seed.get("unidad_id")))for(var parent:ranked)if(parent.has("unidad_id")&&parent.get("unidad_id").equals(r.get("origen"))&&parent.get("documento_id").equals(seed.get("documento_id"))&&parent.get("fuente_pdf_sha256").equals(seed.get("fuente_pdf_sha256")))dependenciesFirst.add(parent);}dependenciesFirst.add(seed);}
  priority=dependenciesFirst;
  for(var c:priority){String id=c.get("id").getAsString();if(selected.containsKey(id))continue;int n=c.get("texto").getAsString().length();if(selected.size()<12&&chars+n<=4000){selected.put(id,c);chars+=n;}else omitted.add(c.get("id"));}
  List<JsonObject> seeds=new ArrayList<>(selected.values());
  for(var seed:seeds){String doc=seed.get("documento_id").getAsString();int whole=ranked.stream().filter(c->c.get("documento_id").getAsString().equals(doc)).mapToInt(c->c.get("texto").getAsString().length()).sum();
   List<JsonObject> related=ranked.stream().filter(c->c.get("documento_id").getAsString().equals(doc)&&(whole<=2200||c.get("seccion").equals(seed.get("seccion")))).sorted(Comparator.comparingInt(c->c.get("orden").getAsInt())).toList();
   for(var c:related)if(!selected.containsKey(c.get("id").getAsString())){int n=c.get("texto").getAsString().length();if(selected.size()<12&&chars+n<=4000){selected.put(c.get("id").getAsString(),c);chars+=n;}else omitted.add(c.get("id"));}
  }
  // Conservar introducciones al recuperar numerales de otra pagina, siempre dentro del corpus filtrado.
  for(var c:new ArrayList<>(selected.values()))if(c.has("relaciones_contexto"))for(var edge:c.getAsJsonArray("relaciones_contexto")){
   var relation=edge.getAsJsonObject();if(c.has("unidad_id")&&relation.get("destino").equals(c.get("unidad_id")))for(var parent:ranked)if(parent.has("unidad_id")&&parent.get("unidad_id").equals(relation.get("origen"))&&!selected.containsKey(parent.get("id").getAsString())){int n=parent.get("texto").getAsString().length();if(chars+n<=4000&&selected.size()<12){selected.put(parent.get("id").getAsString(),parent);chars+=n;}else omitted.add(parent.get("id"));}
  }
  JsonObject result=new JsonObject();result.addProperty("politica",listGroups.isEmpty()?"anclas-dependencias-antes-semillas-expansion-acotada-v4":"anclas-dependencias-listas-propuestas-v4");result.addProperty("caracteres_utf16",chars);result.addProperty("max_caracteres_utf16",4000);result.add("fragmentos",CorpusMultidocumento.JSON.toJsonTree(selected.values().stream().sorted(Comparator.comparing((JsonObject c)->c.get("documento_id").getAsString()).thenComparingInt(c->c.get("orden").getAsInt())).toList()));result.add("grupos_lista_propuestos",ContextoListas.trace(listGroups));result.add("anclas_candidatas",anchors);result.add("expansiones_omitidas_por_presupuesto",omitted);return result;
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=3)throw new IllegalArgumentException("Uso: <indice> <pregunta> <salida-nueva>");Path index=Path.of(args[0]).toRealPath(),out=Path.of(args[2]).toAbsolutePath().normalize();if(Files.exists(out))throw new IllegalArgumentException("Salida existente");
  if(args[1].isBlank()||args[1].length()>1000)throw new IllegalArgumentException("Pregunta invalida");Files.createDirectories(out);
  JsonObject retrieval=BusquedaMultidocumento.search(index,args[1],out.resolve("recuperacion.json"));JsonObject context=context(retrieval);BusquedaLocal.write(out.resolve("contexto.json"),context);
  Map<String,JsonObject> allowed=new LinkedHashMap<>();JsonArray evidence=new JsonArray(),aliases=new JsonArray();int number=0;
  List<JsonObject> sourceChunks=new ArrayList<>();for(var e:context.getAsJsonArray("fragmentos"))sourceChunks.add(e.getAsJsonObject());
  for(var c:ContextoContinuidad.units(ContextoListas.units(sourceChunks,args[1]))){String alias="E"+(++number);allowed.put(alias,c);aliases.add(alias);JsonObject entry=new JsonObject();entry.addProperty("id",alias);
   for(String key:List.of("clave","fecha_emision","vigencia","pagina","orden","texto","fecha_periodo","clave_cita","paginas","seccion"))if(c.has(key))entry.add(key,c.get(key));
   if(c.has("componentes_contexto_lista")){JsonArray ids=new JsonArray();for(var member:ContextoListas.components(c))ids.add(member.get("id"));entry.add("componentes_ids",ids);entry.addProperty("relacion_contexto",c.has("tipo_relacion_contexto")?"Continuación temporal declarada entre páginas; las fuentes se conservan y citan por separado":"Agrupación temporal propuesta de lista; el texto concatena las fuentes sin reescribirlas y se cita por separado");}
   evidence.add(entry);
  }
  BusquedaLocal.write(out.resolve("evidencia-modelo.json"),new com.google.gson.Gson().toJsonTree(Map.of("unidades",evidence,"grupos_lista_propuestos",context.get("grupos_lista_propuestos"))).getAsJsonObject());
  if(CorpusMultidocumento.JSON.toJson(evidence).length()>9000)throw new IllegalArgumentException("Payload de evidencia excede presupuesto del piloto");
  JsonObject result;
  if(allowed.isEmpty()){
   result=new JsonObject();result.addProperty("consulta",args[1]);result.addProperty("estado","SIN_EVIDENCIA");result.addProperty("respuesta","No hay documentos recuperables para las claves solicitadas.\n");result.add("citas",new JsonArray());result.add("partes",new JsonArray());
  }else{
   JsonObject schema=JsonParser.parseString("""
    {"type":"object","properties":{"estado":{"type":"string","enum":["EVIDENCIA_ENCONTRADA","SIN_EVIDENCIA"]},"fragmentos":{"type":"array","maxItems":20,"items":{"type":"string"}}},"required":["estado","fragmentos"],"additionalProperties":false}
   """).getAsJsonObject();schema.getAsJsonObject("properties").getAsJsonObject("fragmentos").getAsJsonObject("items").add("enum",aliases);
   result=RespuestaPorPartes.execute(args[1],evidence,schema,allowed,out);
  }
  if(retrieval.has("posibles_claves_repetidas")){
   Set<String> used=new HashSet<>();for(var e:result.getAsJsonArray("citas")){var c=e.getAsJsonObject();for(String k:List.of("clave","clave_padre"))if(c.has(k)&&!c.get(k).isJsonNull())used.add(c.get(k).getAsString());}
   JsonArray collisions=new JsonArray();for(var e:retrieval.getAsJsonArray("posibles_claves_repetidas"))if(used.contains(e.getAsJsonObject().get("clave").getAsString()))collisions.add(e);
   result.add("advertencias_identidad",collisions);if(!collisions.isEmpty())result.addProperty("respuesta","AVISO DE IDENTIDAD: una clave citada aparece en varios archivos. Distingue la fuente por el PDF y sus datos; no implica que sean el mismo documento.\n\n"+result.get("respuesta").getAsString());
  }
  result.add("politica_contexto",context.get("politica"));result.addProperty("indice_sha256",OcrPagina.hash(index));result.addProperty("modo_corpus","ENSAYO_LOCAL_DE_DESARROLLO_CON_ESTADOS_DE_REVISION");BusquedaLocal.write(out.resolve("respuesta.json"),result);Files.writeString(out.resolve("respuesta.txt"),result.get("respuesta").getAsString(),StandardCharsets.UTF_8);
  System.out.println("RESPUESTA MULTIDOCUMENTO ("+result.get("estado").getAsString()+")\n"+result.get("respuesta").getAsString()+"\nGuardado: "+out);
 }
}


