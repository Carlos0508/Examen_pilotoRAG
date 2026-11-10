package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ingesta genérica de transcripciones revisadas con procedencia multipágina. */
public final class CorpusMultipagina {
 static String compact(String s){return s.replaceAll("\\s+", " ").trim();}
 static List<JsonObject> load(Path configPath)throws Exception {
  var cfg=CorpusMultidocumento.read(configPath);Path base=configPath.getParent();
  if(!cfg.get("formato").getAsString().equals("corpus-multipagina-v1"))throw new IllegalArgumentException("Formato multipágina inválido");
  var approval=CorpusMultidocumento.read(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("aprobacion")));
  if(!approval.get("contenido_revisado_por_usuario").getAsBoolean())throw new IllegalArgumentException("Contenido no revisado");
  if(approval.has("metadatos_confirmados_por_usuario")&&!approval.get("metadatos_confirmados_por_usuario").getAsBoolean())throw new IllegalArgumentException("Metadatos pendientes de confirmación");
  Path reviewed=Path.of(approval.get("lectura_aprobada").getAsString());
  if(!OcrPagina.hash(reviewed).equals(approval.get("sha256_lectura_aprobada").getAsString()))throw new IllegalArgumentException("Lectura aprobada alterada");
  String approvedText=Files.readString(reviewed,StandardCharsets.UTF_8).replace("\r\n","\n").replace("\r","\n");
  var identity=CorpusMultidocumento.read(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("identidad")));
  var critical=CorpusMultidocumento.read(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("confirmacion_criticos")));
  if(critical.has("metadatos_confirmados_por_usuario")&&!critical.get("metadatos_confirmados_por_usuario").getAsBoolean())throw new IllegalArgumentException("Identidad pendiente de confirmación");
  if(!critical.get("clave").equals(cfg.get("clave")))throw new IllegalArgumentException("Confirmación de identidad distinta");
  boolean identityBound=false;for(var r:critical.getAsJsonArray("referencias")){var ref=r.getAsJsonObject();Path p=Path.of(ref.get("archivo").getAsString());if(!OcrPagina.hash(p).equals(ref.get("sha256").getAsString()))throw new IllegalArgumentException("Referencia crítica alterada");if(p.toRealPath().equals(Path.of(cfg.getAsJsonObject("identidad").get("ruta").getAsString()).toRealPath()))identityBound=true;}
  if(!identityBound)throw new IllegalArgumentException("Identidad sin confirmación vinculada");
  Path pdf=Path.of(identity.get("fuente_pdf").getAsString());if(!OcrPagina.hash(pdf).equals(identity.get("fuente_pdf_sha256").getAsString()))throw new IllegalArgumentException("PDF alterado");
  Set<String> approvedSources=new HashSet<>();
  Map<Integer,SortedMap<Integer,Integer>> expected=new TreeMap<>(),actual=new TreeMap<>();
  for(var b:CorpusMultidocumento.rows(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("bloques_aprobados")))){
   if(!b.get("texto_integral_confirmado").getAsBoolean())throw new IllegalArgumentException("Bloque no confirmado");
   for(var e:b.getAsJsonArray("tramos")){var s=e.getAsJsonObject();String raw=source(s);if(!raw.equals(s.get("texto").getAsString())||!approvedText.contains(raw))throw new IllegalArgumentException("Tramo fuera de lectura revisada");add(expected,s);approvedSources.add(sourceIdentity(s));}
  }
  List<JsonObject> chunks=CorpusMultidocumento.rows(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("fragmentos")));int order=0;Set<String> ids=new HashSet<>();
  for(var c:chunks){
   if(!c.get("documento_id").equals(cfg.get("documento_id"))||!c.get("clave").equals(cfg.get("clave"))||!c.get("clave").equals(identity.get("clave_propuesta"))||!c.get("clave_cita").equals(c.get("clave"))||!c.get("asunto").equals(identity.get("asunto_literal_propuesto"))||!c.get("fecha_emision").equals(identity.get("fecha_emision_propuesta"))||!c.get("remitentes").equals(identity.get("remitentes_propuestos"))||!c.get("fuente_pdf").equals(identity.get("fuente_pdf"))||!c.get("fuente_pdf_sha256").equals(identity.get("fuente_pdf_sha256"))||!c.get("vigencia").getAsString().equals("NO_DETERMINADA")||c.get("orden").getAsInt()!=++order||!ids.add(c.get("id").getAsString()))throw new IllegalArgumentException("Metadatos multipágina inconsistentes");
   StringBuilder text=new StringBuilder();SortedSet<Integer> pages=new TreeSet<>();
   for(var e:c.getAsJsonArray("tramos")){var s=e.getAsJsonObject();if(!approvedSources.contains(sourceIdentity(s)))throw new IllegalArgumentException("Fuente no pertenece a la página aprobada");text.append(source(s));add(actual,s);pages.add(s.get("pagina").getAsInt());}
   if(!compact(text.toString()).equals(c.get("texto").getAsString())||c.get("texto").getAsString().length()>2600||!CorpusMultidocumento.JSON.toJsonTree(pages).equals(c.get("paginas"))||c.get("pagina").getAsInt()!=pages.first())throw new IllegalArgumentException("Texto o páginas sin trazabilidad");
  }
  for(var h:cfg.getAsJsonArray("encabezados_no_indexados"))for(var e:h.getAsJsonObject().getAsJsonArray("tramos")){var s=e.getAsJsonObject();if(!source(s).equals(s.get("texto").getAsString()))throw new IllegalArgumentException("Encabezado alterado");add(actual,s);}
  if(!coverage(expected).equals(coverage(actual)))throw new IllegalArgumentException("Cobertura incompleta, duplicada o fuera del cuerpo aprobado");
  return chunks;
 }
 static String sourceIdentity(JsonObject s)throws Exception{return s.get("pagina").getAsInt()+"|"+Path.of(s.get("archivo").getAsString()).toRealPath()+"|"+s.get("sha256").getAsString();}
 static String source(JsonObject s)throws Exception{
  Path p=Path.of(s.get("archivo").getAsString());if(!OcrPagina.hash(p).equals(s.get("sha256").getAsString()))throw new IllegalArgumentException("Página de transcripción alterada");
  String text=Files.readString(p,StandardCharsets.UTF_8).replace("\r\n","\n").replace("\r","\n");int a=s.get("inicio_utf16").getAsInt(),z=s.get("fin_utf16_exclusivo").getAsInt();if(a<0||z<=a||z>text.length())throw new IllegalArgumentException("Offsets inválidos");return text.substring(a,z);
 }
 static void add(Map<Integer,SortedMap<Integer,Integer>> map,JsonObject s){int page=s.get("pagina").getAsInt(),a=s.get("inicio_utf16").getAsInt(),z=s.get("fin_utf16_exclusivo").getAsInt();var spans=map.computeIfAbsent(page,k->new TreeMap<>());if(spans.putIfAbsent(a,z)!=null)throw new IllegalArgumentException("Tramo duplicado");}
 static Map<Integer,String> coverage(Map<Integer,SortedMap<Integer,Integer>> input){Map<Integer,String> result=new TreeMap<>();for(var e:input.entrySet()){int first=-1,last=-1;StringBuilder ranges=new StringBuilder();for(var s:e.getValue().entrySet()){if(first<0){first=s.getKey();last=s.getValue();}else if(s.getKey()<last)throw new IllegalArgumentException("Tramos solapados");else if(s.getKey()==last)last=s.getValue();else{ranges.append(first+":"+last+";");first=s.getKey();last=s.getValue();}}ranges.append(first+":"+last);result.put(e.getKey(),ranges.toString());}return result;}
}
