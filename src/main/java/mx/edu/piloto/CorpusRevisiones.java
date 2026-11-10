package mx.edu.piloto;
import com.google.gson.*;import java.nio.file.*;import java.util.*;
/** Sustituciones versionadas vinculadas a unidades confirmadas y remanentes OCR literales. */
public final class CorpusRevisiones {
 static String render(JsonObject u){
  List<String> lines=new ArrayList<>();
  for(String k:List.of("apartado","contexto_previo_propuesto","titulo","subapartado"))if(u.has(k)&&!u.get(k).isJsonNull())lines.add(u.get(k).getAsString());
  if(u.has("celdas"))for(var e:u.getAsJsonObject("celdas").entrySet())lines.add(e.getKey()+": "+(e.getValue().isJsonNull()?"[celda vacía en el original]":e.getValue().getAsString()));
  for(String k:List.of("texto_propuesto","nota_unidades","condicion_adicional"))if(u.has(k)&&!u.get(k).isJsonNull())lines.add(u.get(k).getAsString());
  return String.join("\n",lines);
 }
 static JsonObject expected(JsonObject template,JsonObject batch,JsonObject item,JsonObject unit,Path cfgDir,Path proposals,Path confirmation)throws Exception{
  var c=template.deepCopy();boolean reviewed=unit!=null;Path source=CorpusMultidocumento.verified(cfgDir,item.getAsJsonObject("texto"));String text=Files.readString(source);
  if(reviewed){if(!text.equals(render(unit)))throw new IllegalArgumentException("Texto no coincide con unidad confirmada");}
  else{Path raw=CorpusMultidocumento.verified(cfgDir,batch.getAsJsonObject("ocr_original"));String full=Files.readString(raw);int a=item.get("inicio_ocr_utf16").getAsInt(),z=item.get("fin_ocr_utf16").getAsInt();if(a<0||z<=a||z>full.length()||!CorpusMultipagina.compact(full.substring(a,z)).equals(text))throw new IllegalArgumentException("Remanente OCR reescrito");}
  if(text.isBlank()||text.length()>3500)throw new IllegalArgumentException("Unidad vacía o excesiva");
  String id="revocr01-"+batch.get("fuente_id").getAsString()+"-p"+String.format(Locale.ROOT,"%03d",batch.get("pagina").getAsInt())+"-"+item.get("unidad").getAsString();
  c.addProperty("id",id);c.addProperty("unidad_id",id);c.addProperty("texto",text);c.addProperty("fuente_texto",source.toString());c.addProperty("fuente_texto_sha256",OcrPagina.hash(source));c.addProperty("inicio_utf16",0);c.addProperty("fin_utf16_exclusivo",text.length());
  c.remove("continuacion_antes");c.remove("continuacion_despues");
  c.addProperty("contenido_confirmado_por_usuario",reviewed);c.addProperty("metadatos_confirmados_por_usuario",false);c.addProperty("nivel_revision",reviewed?"PARCIAL_DATOS_CRITICOS_EN_ENSAYO":"OCR_AUTOMATICO_NO_REVISADO");
  c.addProperty("fragmentacion",reviewed?"UNIDAD_ESTRUCTURADA_CONFIRMADA":"REMANENTE_OCR_LITERAL");c.addProperty("literalidad",reviewed?"Transcripción estructurada confirmada por usuario; no facsímil ni certificación institucional":"Remanente OCR conservado sin revisión; comprobar PDF");
  c.add("origen_ocr",batch.get("ocr_original"));
  if(reviewed&&unit.has("celdas"))c.add("celdas_tabla",unit.get("celdas"));
  if(reviewed){c.addProperty("revision_ocr_unidad",unit.get("id_revision").getAsString());c.addProperty("fuente_version",proposals.toString());c.addProperty("fuente_version_sha256",OcrPagina.hash(proposals));c.addProperty("confirmacion_documental",confirmation.toString());c.addProperty("confirmacion_sha256",OcrPagina.hash(confirmation));}
  if(item.has("relaciones_contexto"))c.add("relaciones_contexto",item.get("relaciones_contexto"));
  return c;
 }
 static List<JsonObject> apply(List<JsonObject> base,Path config)throws Exception{
  Path dir=config.toRealPath().getParent();var cfg=CorpusMultidocumento.read(config);if(!cfg.get("formato").getAsString().equals("revision-ocr-confirmada-v1"))throw new IllegalArgumentException("Revisión desconocida");
  Path pp=CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("propuestas")),cp=CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("confirmacion"));var proposals=CorpusMultidocumento.read(pp);var approval=CorpusMultidocumento.read(cp);
  if(!approval.get("propuestas_sha256").getAsString().equals(OcrPagina.hash(pp)))throw new IllegalArgumentException("La confirmación no corresponde");
  Map<String,JsonObject> units=new HashMap<>();for(var e:proposals.getAsJsonArray("unidades")){var u=e.getAsJsonObject();units.put(u.get("id_revision").getAsString(),u);}
  Set<String> approved=new HashSet<>();for(var e:approval.getAsJsonArray("unidades")){var u=e.getAsJsonObject();if(!u.get("estado").getAsString().equals("CONFIRMADO_POR_USUARIO"))throw new IllegalArgumentException("Unidad no confirmada");approved.add(u.get("id").getAsString());}if(!approved.equals(units.keySet()))throw new IllegalArgumentException("Conjunto de revisión incompleto");
  Map<String,JsonObject> old=new HashMap<>();for(var c:base)old.put(c.get("id").getAsString(),c);Set<String> removed=new HashSet<>(),usedUnits=new HashSet<>(),affected=new HashSet<>();Map<String,List<JsonObject>> inserts=new HashMap<>();
  for(var e:cfg.getAsJsonArray("reemplazos")){
   var batch=e.getAsJsonObject();String doc=batch.get("documento_id").getAsString(),hash=batch.get("pdf_sha256").getAsString();int page=batch.get("pagina").getAsInt();List<String> ids=new ArrayList<>();for(var id:batch.getAsJsonArray("sustituidos"))ids.add(id.getAsString());if(ids.isEmpty())throw new IllegalArgumentException("Sin origen de revisión");
   Set<String> pageIds=new HashSet<>();for(var c:base)if(c.get("documento_id").getAsString().equals(doc)&&c.get("pagina").getAsInt()==page)pageIds.add(c.get("id").getAsString());if(!pageIds.equals(new HashSet<>(ids)))throw new IllegalArgumentException("Sustitución omite fragmentos de página");
   for(String id:ids){var c=old.get(id);if(c==null||!removed.add(id)||!c.get("fuente_pdf_sha256").getAsString().equals(hash)||!c.get("documento_id").getAsString().equals(doc))throw new IllegalArgumentException("Fuente de reemplazo inválida");}
   var template=old.get(ids.getFirst());if(!OcrPagina.hash(Path.of(template.get("fuente_pdf").getAsString())).equals(hash))throw new IllegalArgumentException("PDF alterado");CorpusMultidocumento.verified(dir,batch.getAsJsonObject("ocr_original"));
   List<JsonObject> added=new ArrayList<>();
   for(var d:batch.getAsJsonArray("unidades")){var item=d.getAsJsonObject();JsonObject unit=null;if(item.has("id_revision")){String id=item.get("id_revision").getAsString();unit=units.get(id);if(unit==null||!usedUnits.add(id))throw new IllegalArgumentException("Unidad ajena/duplicada");var source=unit.getAsJsonObject("fuente");if(source.get("pagina_pdf").getAsInt()!=page||!source.get("pdf_sha256").getAsString().equals(hash)||!source.get("texto_ocr_sha256").equals(batch.getAsJsonObject("ocr_original").get("sha256")))throw new IllegalArgumentException("Unidad cruzada entre fuentes");}
    added.add(expected(template,batch,item,unit,dir,pp,cp));
   }
   inserts.put(ids.getFirst(),added);affected.add(doc);
  }
  if(!usedUnits.equals(approved))throw new IllegalArgumentException("Faltan unidades confirmadas");
  List<JsonObject> result=new ArrayList<>();for(var c:base){String id=c.get("id").getAsString();if(inserts.containsKey(id))result.addAll(inserts.get(id));if(!removed.contains(id))result.add(c.deepCopy());}
  Map<String,Integer> order=new HashMap<>();Set<String> seen=new HashSet<>();for(var c:result){String doc=c.get("documento_id").getAsString();if(!seen.add(c.get("id").getAsString()))throw new IllegalArgumentException("ID duplicado");if(affected.contains(doc)){int n=order.getOrDefault(doc,0)+1;order.put(doc,n);c.addProperty("orden",n);}}
  Set<String> unitIds=new HashSet<>();for(var c:result)if(c.has("unidad_id"))unitIds.add(c.get("unidad_id").getAsString());for(var c:result)if(c.get("id").getAsString().startsWith("revocr01-")&&c.has("relaciones_contexto"))for(var edge:c.getAsJsonArray("relaciones_contexto")){var edgeObject=edge.getAsJsonObject();if(result.stream().noneMatch(parent->parent.has("unidad_id")&&parent.get("unidad_id").equals(edgeObject.get("origen"))&&parent.get("documento_id").equals(c.get("documento_id"))&&parent.get("fuente_pdf_sha256").equals(c.get("fuente_pdf_sha256")))||!edgeObject.get("destino").equals(c.get("unidad_id")))throw new IllegalArgumentException("Dependencia incompleta");}
  return result;
 }
 public static void main(String[] a)throws Exception{if(a.length!=2)throw new IllegalArgumentException("Uso: <manifiesto> <fragmentos-nuevos.jsonl>");Path output=Path.of(a[1]);if(Files.exists(output))throw new IllegalArgumentException("Salida existente");StringBuilder text=new StringBuilder();for(var c:CorpusMultidocumento.load(Path.of(a[0])))text.append(CorpusMultidocumento.JSON.toJson(c)).append('\n');Files.writeString(output,text.toString());}
}
