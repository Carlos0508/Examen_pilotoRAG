package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Unidades temporales de contexto; nunca sustituyen la transcripción ni las citas fuente. */
public final class ContextoListas {
 static final int MAX_GROUP_CHARS=2800;
 private static final Pattern HEADER=Pattern.compile("\\b(?:los|las)\\s+siguientes\\s+[\\p{L} ]{1,60}:",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CHARACTER_CLASS);
 private static final Pattern ITEM=Pattern.compile("(?m)^\\s*(?:[•*+«–-]+|[0-9]+[.)])\\s*(.+)$");
 static boolean same(JsonObject a,JsonObject b,String key){return Objects.equals(a.get(key),b.get(key));}
 static List<List<JsonObject>> groups(List<JsonObject> candidates,String question)throws Exception{
  List<JsonObject> ordered=new ArrayList<>(candidates);ordered.sort(Comparator.comparing((JsonObject c)->c.get("documento_id").getAsString()).thenComparingInt(c->c.get("orden").getAsInt()));
  List<List<JsonObject>> result=new ArrayList<>();Set<String> used=new HashSet<>();
  for(int i=0;i+1<ordered.size();i++){
   var parent=ordered.get(i);var child=ordered.get(i+1);
   if(used.contains(parent.get("id").getAsString())||used.contains(child.get("id").getAsString()))continue;
   if(!parent.has("nivel_revision")||!parent.get("nivel_revision").getAsString().equals("OCR_AUTOMATICO_NO_REVISADO")||!same(parent,child,"nivel_revision"))continue;
   if(!same(parent,child,"documento_id")||!parent.has("fuente_pdf_sha256")||!same(parent,child,"fuente_pdf_sha256")||!same(parent,child,"fuente_pdf")||!parent.has("clave")||parent.get("clave").isJsonNull()||!same(parent,child,"clave"))continue;
   if(parent.get("pagina").getAsInt()+1!=child.get("pagina").getAsInt()||parent.get("orden").getAsInt()+1!=child.get("orden").getAsInt())continue;
   if(!same(parent,child,"fecha_emision")||!same(parent,child,"fecha_periodo")||!same(parent,child,"clave_padre")||!same(parent,child,"vigencia")||!same(parent,child,"tipo_documento"))continue;
   String first=parent.get("texto").getAsString(),second=child.get("texto").getAsString();if(first.length()+second.length()+2>MAX_GROUP_CHARS||!HEADER.matcher(first).find()||!child.has("fuente_texto"))continue;
   // La capa de texto ya fue verificada contra sus hashes por verifyIndex.
   String page=Files.readString(Path.of(child.get("fuente_texto").getAsString()));
   if(!ClavesDocumentales.canonicas(page.substring(0,Math.min(700,page.length()))).contains(parent.get("clave").getAsString()))continue;
   var firstItem=ITEM.matcher(page);if(!firstItem.find())continue;
   String prefix=page.substring(0,firstItem.start());boolean newSection=false;
   for(String line:prefix.split("\\R")){String norm=ControlEvidencia.normalized(line).strip();if((norm.contains(":")&&!norm.startsWith("asunto")&&!norm.startsWith("circular"))||norm.matches("(?:[ivxlcdm]+[.)]|[0-9]+[.)])\\s+.*")||norm.startsWith("anexo"))newSection=true;}
   if(newSection)continue;
   var item=ITEM.matcher(page);Set<String> itemTerms=new HashSet<>();int count=0;
   while(item.find()){count++;itemTerms.addAll(BusquedaHibrida.terms(item.group(1)).keySet());}
   if(count<2)continue;
   itemTerms.retainAll(BusquedaHibrida.terms(second).keySet());
   Set<String> queryTerms=new HashSet<>(BusquedaHibrida.terms(BusquedaHibrida.lexicalQuestion(question)).keySet());queryTerms.removeIf(t->t.matches("[0-9]+")||t.length()<4);queryTerms.retainAll(itemTerms);
   boolean enumeration=ControlEvidencia.normalized(question).matches("(?s).*(?:enumera|lista de|cuales.*(?:tramites|operaciones)|que.*tramites).*");
   if(queryTerms.size()<2&&!enumeration)continue;
   result.add(List.of(parent,child));used.add(parent.get("id").getAsString());used.add(child.get("id").getAsString());
  }
  return result;
 }
 static List<JsonObject> components(JsonObject unit){
  if(!unit.has("componentes_contexto_lista"))return List.of(unit);
  List<JsonObject> result=new ArrayList<>();for(var e:unit.getAsJsonArray("componentes_contexto_lista"))result.add(e.getAsJsonObject());return result;
 }
 static JsonObject unit(List<JsonObject> group){
  var first=group.getFirst();JsonObject result=new JsonObject();
  for(String key:List.of("documento_id","clave","clave_cita","clave_padre","fecha_emision","fecha_periodo","vigencia","pagina","orden","fuente_pdf","fuente_pdf_sha256","fuente_nombre","tipo_documento","nivel_revision","contenido_confirmado_por_usuario","metadatos_confirmados_por_usuario"))if(first.has(key))result.add(key,first.get(key));
  result.addProperty("id","grupo-lista-"+first.get("id").getAsString());result.addProperty("unidad_id","Grupo temporal de continuidad de lista");result.addProperty("seccion","CONTEXTO_LISTA_PROPUESTO_NO_CONFIRMADO");result.addProperty("tipo_unidad_contexto","AGRUPACION_TEMPORAL_NO_ES_FRAGMENTO_DEL_INDICE");result.addProperty("texto",String.join("\n\n",group.stream().map(c->c.get("texto").getAsString()).toList()));result.add("paginas",CorpusMultidocumento.JSON.toJsonTree(group.stream().map(c->c.get("pagina").getAsInt()).distinct().toList()));result.add("componentes_contexto_lista",CorpusMultidocumento.JSON.toJsonTree(group));return result;
 }
 static List<JsonObject> units(List<JsonObject> chunks,String question)throws Exception{
  var groups=groups(chunks,question);Map<String,JsonObject> replacements=new HashMap<>();Set<String> consumed=new HashSet<>();
  for(var group:groups){replacements.put(group.getFirst().get("id").getAsString(),unit(group));for(var c:group)consumed.add(c.get("id").getAsString());}
  List<JsonObject> result=new ArrayList<>();for(var c:chunks){String id=c.get("id").getAsString();if(replacements.containsKey(id))result.add(replacements.get(id));else if(!consumed.contains(id))result.add(c);}return result;
 }
 // Requisito léxico necesario, no suficiencia semántica: no extender una lista
 // a un objeto nombrado que ni siquiera aparece en las fuentes seleccionadas.
 static String rejection(String question,String text,Map<String,JsonObject> allowed){
  if(allowed.values().stream().noneMatch(c->c.has("componentes_contexto_lista")))return "";
  int open=question.indexOf('¿'),close=question.lastIndexOf('?');if(open<0||close<open)return "";
  String body=question.substring(open+1,close);
  if(Pattern.compile("\\b(?:que|quien|quienes|cual|cuales|cuando|donde|como|cuanto|cuantos)\\b").matcher(ControlEvidencia.normalized(body)).find())return "";
  var operands=Pattern.compile(".*?\\b((?:el|la|los|las)\\s+.+?)\\s+y\\s+((?:el|la|los|las)\\s+.+)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CHARACTER_CLASS).matcher(body);
  if(!operands.matches())return "";
  Set<String> available=new HashSet<>();for(String term:BusquedaHibrida.terms(text).keySet())available.addAll(forms(term));
  for(int i=1;i<=2;i++)for(String term:BusquedaHibrida.terms(operands.group(i)).keySet()){
   if(term.length()<4||term.matches("[0-9]+"))continue;
   if(Collections.disjoint(forms(term),available))return "ELEMENTO_COORDINADO_NO_EXPLICITO_EN_CONTEXTO_LISTA";
  }
  return "";
 }
 static Set<String> forms(String word){Set<String> result=new HashSet<>();result.add(word);if(word.length()>4&&word.endsWith("s"))result.add(word.substring(0,word.length()-1));if(word.length()>5&&word.endsWith("es"))result.add(word.substring(0,word.length()-2));return result;}
 static JsonArray trace(List<List<JsonObject>> groups){JsonArray result=new JsonArray();for(var group:groups){JsonObject row=new JsonObject();row.addProperty("estado","CONTINUIDAD_ESTRUCTURAL_PROPUESTA_NO_CONFIRMADA");row.addProperty("politica","mismo-documento-pdf-clave-paginas-orden-consecutivos-cabecera-y-vinetas-v1");row.add("componentes_ids",CorpusMultidocumento.JSON.toJsonTree(group.stream().map(c->c.get("id").getAsString()).toList()));row.add("paginas",CorpusMultidocumento.JSON.toJsonTree(group.stream().map(c->c.get("pagina").getAsInt()).toList()));result.add(row);}return result;}
}
