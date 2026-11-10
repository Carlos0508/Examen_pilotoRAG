package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Adaptador de fuentes revisadas y fuentes de ensayo parcialmente revisadas. */
public final class CorpusMultidocumento {
 static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
 static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p,StandardCharsets.UTF_8)).getAsJsonObject();}
 static Path verified(Path parent,JsonObject ref)throws Exception{Path p=parent.resolve(ref.get("ruta").getAsString()).normalize().toRealPath();if(!OcrPagina.hash(p).equals(ref.get("sha256").getAsString()))throw new IllegalArgumentException("Entrada alterada: "+p);return p;}
 static List<JsonObject> rows(Path p)throws Exception{List<JsonObject> rows=new ArrayList<>();for(String line:Files.readAllLines(p,StandardCharsets.UTF_8))if(!line.isBlank())rows.add(JsonParser.parseString(line).getAsJsonObject());return rows;}
 static List<JsonObject> load(Path manifest)throws Exception{
  JsonObject m=read(manifest);if(!m.get("modo").getAsString().equals("ENSAYO_LOCAL_DE_DESARROLLO"))throw new IllegalArgumentException("Modo no admitido");
  Path legacy=verified(manifest.getParent(),m.getAsJsonObject("legado"));ValidarCorpus.validate(legacy);
  List<JsonObject> normalized=new ArrayList<>();
  for(var doc:read(legacy).getAsJsonArray("documentos")){
   var d=doc.getAsJsonObject();Path chunks=legacy.getParent().resolve(d.get("fragmentos").getAsString()).normalize();
   for(var chunk:rows(chunks)){var c=chunk.deepCopy();c.addProperty("tipo_documento","CIRCULAR");c.addProperty("clave_cita",c.get("clave").getAsString());c.addProperty("nivel_revision","REFERENCIA_O_CUERPO_CONFIRMADO_USUARIO");c.addProperty("seccion","CUERPO");c.addProperty("literalidad","Texto literal de transcripcion confirmada, no certificacion institucional");if(c.has("normalizacion_autorizada"))c.addProperty("literalidad","Texto de transcripcion revisada y confirmada con normalizacion autorizada del portal; no facsimil del PDF");normalized.add(c);}
  }
  var structured=m.getAsJsonObject("estructurado");Path plan=verified(manifest.getParent(),structured.getAsJsonObject("plan")),candidateFile=verified(manifest.getParent(),structured.getAsJsonObject("candidatos")),excludedFile=verified(manifest.getParent(),structured.getAsJsonObject("excluidos"));
  JsonObject config=read(plan);Map<String,Path> inputs=new HashMap<>();for(var e:config.getAsJsonObject("entradas").entrySet())inputs.put(e.getKey(),verified(plan.getParent(),e.getValue().getAsJsonObject()));
  JsonObject review=read(inputs.get("revision_anexo")),classification=read(inputs.get("relacion_documental"));
  if(!classification.get("sha256").getAsString().equals(OcrPagina.hash(inputs.get("pdf"))))throw new IllegalArgumentException("PDF de expediente alterado");
  boolean relation=false;for(var e:classification.getAsJsonArray("relaciones_propuestas")){var r=e.getAsJsonObject();if(r.get("origen").getAsString().equals("doc-010")&&r.get("destino").getAsString().equals("doc-009")&&r.get("tipo").getAsString().equals("ANEXO_DE")&&r.get("estado").getAsString().equals("CONFIGURADO_POR_USUARIO_PARA_PROTOTIPO"))relation=true;}if(!relation)throw new IllegalArgumentException("Relacion anexo no configurada");
  Set<String> excluded=new HashSet<>();JsonArray excludedSteps=new JsonArray();for(var e:review.getAsJsonArray("pendientes")){var p=e.getAsJsonObject();if(p.has("unidad"))excluded.add(p.get("unidad").getAsString());}
  Set<String> foundExcluded=new HashSet<>();for(var e:rows(excludedFile)){String id=e.get("unidad_id").getAsString();if(!excluded.contains(id)||!foundExcluded.add(id))throw new IllegalArgumentException("Exclusion invalida");if(!e.get("numero").isJsonNull())excludedSteps.add(e.get("numero"));}if(!foundExcluded.equals(excluded))throw new IllegalArgumentException("Faltan exclusiones");
  Map<String,JsonObject> originalUnits=new HashMap<>(),documents=new HashMap<>();for(var e:rows(inputs.get("unidades")))originalUnits.put(e.get("id").getAsString(),e);for(var e:config.getAsJsonArray("documentos")){var d=e.getAsJsonObject();documents.put(d.get("documento_id").getAsString(),d);}
  var annex=documents.get("doc-010");if(!annex.get("fecha_emision").isJsonNull()||!annex.get("clave").isJsonNull()||!annex.get("precision_fecha").getAsString().equals("MES"))throw new IllegalArgumentException("Anexo con herencia incorrecta");
  Set<String> seenUnits=new HashSet<>();
  for(var f:rows(candidateFile)){
   String uid=f.get("unidad_id").getAsString();var original=originalUnits.get(uid);var metadata=f.getAsJsonObject("documento");String docId=metadata.get("documento_id").getAsString();
   if(original==null||excluded.contains(uid)||!seenUnits.add(uid)||!f.get("candidato_para_revision").getAsBoolean()||!metadata.equals(documents.get(docId))||f.get("pagina").getAsInt()!=original.get("pagina").getAsInt()||f.get("pagina").getAsInt()==4)throw new IllegalArgumentException("Candidato excluido o no corresponde con revision");
   String literal=original.has("texto_literal_propuesto")?original.get("texto_literal_propuesto").getAsString():original.get("texto_propuesto").getAsString();if(!f.get("texto_fuente_propuesto").getAsString().equals(literal))throw new IllegalArgumentException("Texto de candidato alterado");
   if(!OcrPagina.hash(Path.of(f.get("fuente_pdf").getAsString())).equals(f.get("fuente_pdf_sha256").getAsString()))throw new IllegalArgumentException("PDF alterado");
   for(var e:f.getAsJsonArray("procedencia_ocr")){var r=e.getAsJsonObject();if(!OcrPagina.hash(Path.of(r.get("archivo_ocr_absoluto").getAsString())).equals(r.get("archivo_ocr_sha256").getAsString()))throw new IllegalArgumentException("Procedencia OCR alterada");}
   JsonObject c=new JsonObject();c.add("id",f.get("id"));c.addProperty("documento_id",docId);c.add("unidad_id",f.get("unidad_id"));c.add("pagina",f.get("pagina"));c.add("seccion",f.get("seccion"));c.add("numero",f.get("numero"));c.addProperty("orden",seenUnits.size());c.addProperty("texto",literal);c.add("clave",metadata.get("clave"));c.add("fecha_emision",metadata.get("fecha_emision"));c.add("remitente_nombre",metadata.get("remitente_nombre"));c.add("vigencia",metadata.get("vigencia"));c.add("anexo_de",metadata.get("anexo_de"));c.addProperty("tipo_documento",metadata.get("tipo").getAsString());
   boolean isAnnex=!metadata.get("anexo_de").isJsonNull();c.addProperty("clave_cita",isAnnex?"Anexo de "+documents.get(metadata.get("anexo_de").getAsString()).get("clave").getAsString():metadata.get("clave").getAsString());
   if(isAnnex){c.add("clave_padre",documents.get(metadata.get("anexo_de").getAsString()).get("clave"));c.add("fecha_periodo",metadata.get("fecha_periodo"));c.add("pasos_excluidos",excludedSteps);}
   if(f.has("texto_editorial_autorizado")){if(!original.has("texto_editorial_autorizado")||!f.get("texto_editorial_autorizado").equals(original.get("texto_editorial_autorizado")))throw new IllegalArgumentException("Edicion editorial no coincide con revision");c.add("texto_editorial_autorizado",f.get("texto_editorial_autorizado"));}
   c.addProperty("nivel_revision","PARCIAL_DATOS_CRITICOS_EN_ENSAYO");c.addProperty("literalidad","Cita de transcripcion propuesta con datos criticos revisados; no certificacion de literalidad PDF");c.addProperty("modo_uso","ENSAYO_LOCAL_DE_DESARROLLO");c.addProperty("fuente_version",candidateFile.toString());c.addProperty("fuente_version_sha256",OcrPagina.hash(candidateFile));c.add("fuente_pdf",f.get("fuente_pdf"));c.add("fuente_pdf_sha256",f.get("fuente_pdf_sha256"));c.add("relaciones_contexto",f.get("relaciones_contexto"));normalized.add(c);
  }
  Set<String> expected=new HashSet<>(originalUnits.keySet());expected.removeAll(excluded);if(!seenUnits.equals(expected))throw new IllegalArgumentException("Candidatos incompletos");
  if(m.has("multipagina"))for(var ref:m.getAsJsonArray("multipagina"))normalized.addAll(CorpusMultipagina.load(verified(manifest.getParent(),ref.getAsJsonObject())));
  if(m.has("automatico"))normalized.addAll(CorpusAutomatico.load(verified(manifest.getParent(),m.getAsJsonObject("automatico"))));
  if(m.has("altas"))for(var ref:m.getAsJsonArray("altas"))normalized.addAll(CorpusAltas.load(verified(manifest.getParent(),ref.getAsJsonObject())));
  if(m.has("revisiones_ocr"))for(var ref:m.getAsJsonArray("revisiones_ocr"))normalized=CorpusRevisiones.apply(normalized,verified(manifest.getParent(),ref.getAsJsonObject()));
  if(m.has("revisiones_metadatos"))for(var ref:m.getAsJsonArray("revisiones_metadatos"))normalized=CorpusMetadatos.apply(normalized,verified(manifest.getParent(),ref.getAsJsonObject()));
  if(m.has("correcciones_confirmadas"))for(var ref:m.getAsJsonArray("correcciones_confirmadas"))normalized=CorpusCorreccionesConfirmadas.apply(normalized,verified(manifest.getParent(),ref.getAsJsonObject()));
  Set<String> ids=new HashSet<>();for(var c:normalized)if(!ids.add(c.get("id").getAsString()))throw new IllegalArgumentException("ID global duplicado");return normalized;
 }
 static void index(Path manifest,Path out)throws Exception{
  if(Files.exists(out))throw new IllegalArgumentException("Salida existente");manifest=manifest.toRealPath();List<JsonObject> chunks=load(manifest);String revision=BusquedaLocal.digest();JsonArray entries=new JsonArray();
  for(int start=0;start<chunks.size();start+=8){List<JsonObject> batch=chunks.subList(start,Math.min(start+8,chunks.size()));List<String> texts=batch.stream().map(c->c.get("texto").getAsString()).toList();var vectors=BusquedaLocal.embed(texts);for(int i=0;i<batch.size();i++){JsonObject e=new JsonObject();e.add("fragmento",batch.get(i));e.add("vector",JSON.toJsonTree(vectors.get(i)));entries.add(e);}}
  if(!revision.equals(BusquedaLocal.digest()))throw new IllegalArgumentException("Cambio de modelo");
  JsonObject index=new JsonObject();index.addProperty("formato","indice-multidocumento-v1");index.addProperty("modo","ENSAYO_LOCAL_DE_DESARROLLO");index.addProperty("manifiesto",manifest.toString());index.addProperty("manifiesto_sha256",OcrPagina.hash(manifest));index.addProperty("modelo",BusquedaLocal.MODEL);index.addProperty("modelo_digest",revision);index.addProperty("dimensiones",1024);index.addProperty("normalizacion","L2");index.add("entradas",entries);
  Files.createDirectories(out);Files.writeString(out.resolve("indice.json"),JSON.toJson(index)+"\n",StandardCharsets.UTF_8);System.out.println("Indice de ensayo: "+entries.size()+" fragmentos. "+out);
 }
 static void verifyIndex(JsonObject index)throws Exception{
  Path manifest=Path.of(index.get("manifiesto").getAsString());if(!OcrPagina.hash(manifest).equals(index.get("manifiesto_sha256").getAsString()))throw new IllegalArgumentException("Manifiesto alterado");Map<String,JsonObject> expected=new HashMap<>();for(var c:load(manifest))expected.put(c.get("id").getAsString(),c);Set<String> seen=new HashSet<>();for(var entry:index.getAsJsonArray("entradas")){var c=entry.getAsJsonObject().getAsJsonObject("fragmento");String id=c.get("id").getAsString();if(!seen.add(id)||!c.equals(expected.get(id)))throw new IllegalArgumentException("Indice no coincide con corpus");}if(!seen.equals(expected.keySet()))throw new IllegalArgumentException("Indice incompleto");
 }
 public static void main(String[] args)throws Exception{if(args.length!=2)throw new IllegalArgumentException("Uso: <manifiesto> <salida-nueva>");index(Path.of(args[0]),Path.of(args[1]));}
}
