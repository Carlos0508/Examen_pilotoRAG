package mx.edu.piloto;
import com.google.gson.*;import java.nio.file.*;import java.time.*;import java.util.*;
/** Confirmaciones por tipo y fragmentos originales exactos; conserva fechas parciales y políticas. */
public final class CorpusMetadatosEspeciales {
 static List<JsonObject> apply(List<JsonObject> base,Path config)throws Exception{
  Path dir=config.toRealPath().getParent();var cfg=CorpusMultidocumento.read(config);
  Path pp=CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("referencias")),ap=CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("confirmacion")),op=CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("originales"));
  var approval=CorpusMultidocumento.read(ap);var proposals=CorpusMultidocumento.read(pp);
  if(!approval.get("estado").getAsString().equals("CONFIRMADO_POR_USUARIO")||!approval.get("propuestas_sha256").getAsString().equals(OcrPagina.hash(pp))||approval.get("texto_literal").getAsString().isBlank())throw new IllegalArgumentException("Confirmación ajena al lote");
  Set<String> approved=new HashSet<>();for(var e:approval.getAsJsonArray("unidades"))if(!approved.add(e.getAsString()))throw new IllegalArgumentException("Confirmación duplicada");
  Set<String> unitIds=new HashSet<>();Map<String,JsonObject> units=new HashMap<>();for(var e:proposals.getAsJsonArray("unidades")){var u=e.getAsJsonObject();if(!unitIds.add(u.get("id").getAsString())||units.put(u.get("documento_id").getAsString(),u)!=null)throw new IllegalArgumentException("Unidad repetida");}if(!unitIds.equals(approved))throw new IllegalArgumentException("Confirmación incompleta");
  Set<String> targets=new HashSet<>();for(var e:cfg.getAsJsonArray("documentos"))if(!targets.add(e.getAsString()))throw new IllegalArgumentException("Documento repetido");if(!targets.equals(units.keySet()))throw new IllegalArgumentException("Documentos ajenos al lote");
  Map<String,JsonObject> originals=new HashMap<>();for(var c:CorpusMultidocumento.rows(op)){if(!targets.contains(c.get("documento_id").getAsString())||originals.put(c.get("id").getAsString(),c)!=null)throw new IllegalArgumentException("Origen inválido");}
  Set<String> seen=new HashSet<>(),docs=new HashSet<>();List<JsonObject> result=new ArrayList<>();
  for(var original:base){var c=original.deepCopy();String doc=c.get("documento_id").getAsString();if(targets.contains(doc)){
   String id=c.get("id").getAsString();var u=units.get(doc);if(!seen.add(id)||!original.equals(originals.get(id))||!c.get("fuente_pdf_sha256").equals(u.get("pdf_sha256"))||!CorpusMetadatos.value(c,"fuente_id").equals(CorpusMetadatos.value(u,"fuente_id"))||!OcrPagina.hash(Path.of(c.get("fuente_pdf").getAsString())).equals(u.get("pdf_sha256").getAsString())||!c.get("tipo_documento").equals(u.get("tipo_documento")))throw new IllegalArgumentException("Origen, PDF, tipo o fragmento cruzado");
   String type=c.get("tipo_documento").getAsString();if(u.has("tipo_documento_propuesto")){
    if(!type.equals("DOCUMENTO_CARGADO_NO_CLASIFICADO")||!u.get("tipo_documento_propuesto").getAsString().equals("CIRCULAR")||u.get("clave").isJsonNull())throw new IllegalArgumentException("Cambio de clasificación no admitido");
    c.addProperty("tipo_documento_previo",type);c.add("tipo_documento",u.get("tipo_documento_propuesto"));
   }
   String precision=u.get("precision_fecha").getAsString();boolean full=!u.get("fecha_emision").isJsonNull();
   if(full){LocalDate.parse(u.get("fecha_emision").getAsString());if(!precision.equals("DIA"))throw new IllegalArgumentException("Precisión de fecha incorrecta");}
   else if(precision.equals("MES"))YearMonth.parse(u.get("fecha_periodo").getAsString());else if(precision.equals("ANIO"))Year.of(u.get("anio_documento").getAsInt());else if(!Set.of("NO_ASIGNADA_AL_CONJUNTO","NO_IDENTIFICADA_PROPIA","NO_IDENTIFICADA_EN_ENCABEZADO_Y_CIERRE").contains(precision))throw new IllegalArgumentException("Fecha desconocida sin alcance");
   if(u.has("fecha_pieza_principal"))LocalDate.parse(u.get("fecha_pieza_principal").getAsString());
   if(type.contains("ANEXO")&&!u.get("clave").isJsonNull()||!full&&type.equals("CIRCULAR_O_CONTINUACION_AUTOMATICA"))throw new IllegalArgumentException("Herencia o precisión no autorizada");
   for(String field:List.of("clave","fecha_emision","fecha_periodo","anio_documento"))if(!CorpusMetadatos.value(original,field).isJsonNull()&&!CorpusMetadatos.value(original,field).equals(CorpusMetadatos.value(u,field)))throw new IllegalArgumentException("Dato previo sustituido");
   var names=u.getAsJsonArray("remitentes_nombres");List<String> joined=new ArrayList<>();for(var n:names){if(n.getAsString().isBlank())throw new IllegalArgumentException("Nombre vacío");joined.add(n.getAsString());}
   if(names.isEmpty()?!u.get("remitente_nombre").isJsonNull():!String.join("; ",joined).equals(u.get("remitente_nombre").getAsString()))throw new IllegalArgumentException("Lista de nombres inconsistente");
   for(String field:List.of("clave","fecha_emision","asunto","remitente_nombre","remitentes_nombres"))c.add(field,u.get(field));
   for(String field:List.of("precision_fecha","fecha_tipo","fecha_periodo","anio_documento","fecha_pieza_principal","pagina_fecha_pieza","partes_institucionales","descripcion_editorial","autor_institucional","numero_oficio","destinatario","referencia_clave_visible","titulo_documento"))if(u.has(field)&&!u.get(field).isJsonNull())c.add(field,u.get(field));
   c.addProperty("asunto_estado",u.get("asunto").isJsonNull()?"SIN_ENCABEZADO_DE_ASUNTO_EXPRESO_IDENTIFICADO":"ASUNTO_EXPRESO_CONFIRMADO");
   String label=c.get("clave_cita").getAsString();if(!u.get("clave").isJsonNull())label=u.get("clave").getAsString();else if(type.equals("OFICIO"))label="Oficio "+u.get("numero_oficio").getAsString();else if(!type.contains("ANEXO")&&u.has("titulo_documento")&&!u.get("titulo_documento").isJsonNull())label=u.get("titulo_documento").getAsString();
   if(c.has("fuente_nombre")&&!label.contains(c.get("fuente_nombre").getAsString()))label+=" | "+c.get("fuente_nombre").getAsString();c.addProperty("clave_cita",label);
   c.addProperty("clave_estado",u.get("clave").isJsonNull()?"SIN_CLAVE_PROPIA_CONFIRMADO_CON_ALCANCE":"CONFIRMADA_EN_REFERENCIAS_USUARIO");c.addProperty("metadatos_confirmados_por_usuario",true);c.addProperty("metadatos_nota_alcance",u.get("nota").getAsString());
   c.add("metadatos_campos_confirmados",CorpusMultidocumento.JSON.toJsonTree(List.of("clave_o_ausencia_propia","fecha_y_precision_o_desconocida","asunto_o_ausencia_identificada","remitentes_o_ausencia_identificada","campos_especiales_con_alcance_en_propuesta")));
   c.addProperty("metadatos_referencias",pp.toString());c.addProperty("metadatos_referencias_sha256",OcrPagina.hash(pp));c.addProperty("metadatos_confirmacion",ap.toString());c.addProperty("metadatos_confirmacion_sha256",OcrPagina.hash(ap));docs.add(doc);
  }result.add(c);}
  if(!seen.equals(originals.keySet())||!docs.equals(targets))throw new IllegalArgumentException("Origen incompleto");return result;
 }
}
