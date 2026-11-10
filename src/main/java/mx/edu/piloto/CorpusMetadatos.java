package mx.edu.piloto;
import com.google.gson.*;import java.nio.file.*;import java.time.LocalDate;import java.util.*;
/** Metadatos documentales respaldados por referencias confirmadas; no modifica el cuerpo OCR. */
public final class CorpusMetadatos {
 static JsonElement value(JsonObject c,String k){return c.has(k)?c.get(k):JsonNull.INSTANCE;}
 static List<JsonObject> apply(List<JsonObject> base,Path config)throws Exception{
  var cfg=CorpusMultidocumento.read(config);Path dir=config.toRealPath().getParent();
  if(cfg.get("formato").getAsString().equals("metadatos-tipos-confirmados-v1"))return CorpusMetadatosEspeciales.apply(base,config);
  String format=cfg.get("formato").getAsString();if(!Set.of("metadatos-referencias-confirmadas-v1","metadatos-lote-confirmado-v1").contains(format))throw new IllegalArgumentException("Formato de metadatos desconocido");
  Path refsPath=CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("referencias")),approvalPath=CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("confirmacion"));
  var refs=CorpusMultidocumento.read(refsPath);var approval=CorpusMultidocumento.read(approvalPath);
  JsonArray cases;
  if(format.equals("metadatos-referencias-confirmadas-v1")){
   if(!approval.get("referencias_sha256").getAsString().equals(OcrPagina.hash(refsPath))||approval.get("casos").getAsInt()!=refs.getAsJsonArray("casos").size()||!approval.get("texto_literal").getAsString().equals("Confirmo las 24 referencias de la prueba final Qdrant"))throw new IllegalArgumentException("Confirmación no corresponde a referencias");
   cases=refs.getAsJsonArray("casos");
  }else{
   if(!approval.get("estado").getAsString().equals("CONFIRMADO_POR_USUARIO")||!approval.get("propuestas_sha256").getAsString().equals(OcrPagina.hash(refsPath))||approval.get("texto_literal").getAsString().isBlank())throw new IllegalArgumentException("Lote sin confirmación correspondiente");
   Set<String> approved=new HashSet<>();for(var id:approval.getAsJsonArray("unidades"))if(!approved.add(id.getAsString()))throw new IllegalArgumentException("Confirmación duplicada");
   Set<String> proposed=new HashSet<>();cases=new JsonArray();
   for(var entry:refs.getAsJsonArray("unidades")){
    var u=entry.getAsJsonObject();if(!proposed.add(u.get("id").getAsString()))throw new IllegalArgumentException("Propuesta duplicada");
    var c=new JsonObject();for(String k:List.of("documento_id","fuente_id","pdf_sha256"))c.add(k,u.get(k));
    for(String k:List.of("tipo_documento","valores_previos"))if(u.has(k))c.add(k,u.get(k));
    var meta=new JsonObject();meta.add("clave_visible_normalizada",u.get("clave"));meta.add("fecha_emision",u.get("fecha_emision"));meta.add("asunto_expreso",u.get("asunto"));var names=new JsonArray();
    if(u.has("remitentes_nombres")){names=u.getAsJsonArray("remitentes_nombres").deepCopy();List<String> full=new ArrayList<>();for(var name:names)full.add(name.getAsString());if(!String.join("; ",full).equals(u.get("remitente_nombre").getAsString()))throw new IllegalArgumentException("Lista de remitentes inconsistente");}else names.add(u.get("remitente_nombre"));
    if(u.has("titulo_documento")&&!u.get("titulo_documento").isJsonNull())meta.add("titulo_documento",u.get("titulo_documento"));
    meta.add("remitentes",names);c.add("metadatos_referencia_propuestos",meta);cases.add(c);
   }if(!approved.equals(proposed))throw new IllegalArgumentException("Confirmación incompleta o ajena al lote");
  }
  Map<String,JsonObject> byDoc=new LinkedHashMap<>();
  for(var item:cases){
   var c=item.getAsJsonObject();String id=c.get("documento_id").getAsString();var previous=byDoc.putIfAbsent(id,c);
   if(previous!=null&&(!previous.get("metadatos_referencia_propuestos").equals(c.get("metadatos_referencia_propuestos"))||!previous.get("pdf_sha256").equals(c.get("pdf_sha256"))||!previous.get("fuente_id").equals(c.get("fuente_id"))))throw new IllegalArgumentException("Referencias documentales contradictorias");
  }
  Set<String> targets=new HashSet<>();for(var e:cfg.getAsJsonArray("documentos"))if(!targets.add(e.getAsString())||!byDoc.containsKey(e.getAsString()))throw new IllegalArgumentException("Documento ajeno o duplicado");
  if(!targets.equals(byDoc.keySet()))throw new IllegalArgumentException("Lote de referencias incompleto");
  Set<String> seen=new HashSet<>();List<JsonObject> result=new ArrayList<>();
  for(var original:base){var c=original.deepCopy();String id=c.get("documento_id").getAsString();
   if(targets.contains(id)){
    var ref=byDoc.get(id);var meta=ref.getAsJsonObject("metadatos_referencia_propuestos");
    String type=c.get("tipo_documento").getAsString();boolean explicit=ref.has("tipo_documento");
    if(!c.get("fuente_pdf_sha256").equals(ref.get("pdf_sha256"))||!value(c,"fuente_id").equals(value(ref,"fuente_id"))||!OcrPagina.hash(Path.of(c.get("fuente_pdf").getAsString())).equals(ref.get("pdf_sha256").getAsString())||c.has("clave_padre")||c.has("anexo_de")&&!c.get("anexo_de").isJsonNull()||!(type.equals("CIRCULAR_O_CONTINUACION_AUTOMATICA")||explicit&&Set.of("CIRCULAR","DOCUMENTO_NORMATIVO_SIN_CLAVE").contains(type))||explicit&&!c.get("tipo_documento").equals(ref.get("tipo_documento")))throw new IllegalArgumentException("Metadatos cruzados con otro PDF, tipo o anexo");
    if(ref.has("valores_previos"))for(var entry:ref.getAsJsonObject("valores_previos").entrySet())if(!value(c,entry.getKey()).equals(entry.getValue()))throw new IllegalArgumentException("Cambió un dato previo al lote");
    LocalDate.parse(meta.get("fecha_emision").getAsString());boolean withoutKey=meta.get("clave_visible_normalizada").isJsonNull();String key=withoutKey?null:meta.get("clave_visible_normalizada").getAsString();
    if(withoutKey?!(type.equals("DOCUMENTO_NORMATIVO_SIN_CLAVE")&&meta.has("titulo_documento")&&!meta.get("titulo_documento").getAsString().isBlank()):key.isBlank())throw new IllegalArgumentException("Clave/título no corresponde al tipo documental");
    var names=meta.getAsJsonArray("remitentes");if(names.isEmpty())throw new IllegalArgumentException("Remitente vacío");List<String> joined=new ArrayList<>();for(var name:names){if(name.getAsString().isBlank())throw new IllegalArgumentException("Remitente vacío");joined.add(name.getAsString());}
    if(ref.has("valores_previos"))for(String field:List.of("clave","fecha_emision","asunto","remitente_nombre")){String incoming=switch(field){case "clave"->"clave_visible_normalizada";case "asunto"->"asunto_expreso";default->field;};JsonElement updated=field.equals("remitente_nombre")?new JsonPrimitive(String.join("; ",joined)):meta.get(incoming);if(!value(c,field).isJsonNull()&&!value(c,field).equals(updated))throw new IllegalArgumentException("El lote no autoriza reemplazar un dato previo revisado");}
    c.add("clave",meta.get("clave_visible_normalizada"));String cite=withoutKey?meta.get("titulo_documento").getAsString():key;if(c.has("fuente_nombre")&&!c.get("fuente_nombre").isJsonNull())cite+=" | "+c.get("fuente_nombre").getAsString();c.addProperty("clave_cita",cite);c.add("fecha_emision",meta.get("fecha_emision"));c.add("asunto",meta.get("asunto_expreso"));c.addProperty("asunto_estado",meta.get("asunto_expreso").isJsonNull()?"SIN_ENCABEZADO_DE_ASUNTO_EXPRESO_IDENTIFICADO":"ASUNTO_EXPRESO_CONFIRMADO");
    if(meta.has("titulo_documento"))c.add("titulo_documento",meta.get("titulo_documento"));
    c.addProperty("remitente_nombre",String.join("; ",joined));c.add("remitentes_nombres",names.deepCopy());
    c.addProperty("metadatos_confirmados_por_usuario",true);c.addProperty("clave_estado",withoutKey?"SIN_CLAVE_PROPIA_CONFIRMADO_USUARIO":"CONFIRMADA_EN_REFERENCIAS_USUARIO");
    c.add("metadatos_campos_confirmados",CorpusMultidocumento.JSON.toJsonTree(List.of("clave","fecha_emision","asunto_expreso_o_ausencia_identificada","remitentes_nombres")));
    if(meta.has("titulo_documento"))c.getAsJsonArray("metadatos_campos_confirmados").add("titulo_documento");
    c.addProperty("metadatos_referencias",refsPath.toString());c.addProperty("metadatos_referencias_sha256",OcrPagina.hash(refsPath));c.addProperty("metadatos_confirmacion",approvalPath.toString());c.addProperty("metadatos_confirmacion_sha256",OcrPagina.hash(approvalPath));
    seen.add(id);
   }result.add(c);
  }
  if(!seen.equals(targets))throw new IllegalArgumentException("Documento confirmado ausente del corpus");return result;
 }
}
