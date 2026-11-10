package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Segmentacion de unidades con estructura y procedencia; no habilita propuestas para RAG. */
public final class FragmentarEstructurado {
 static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
 static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p,StandardCharsets.UTF_8)).getAsJsonObject();}
 static Path verified(Path plan,JsonObject entry)throws Exception{
  Path p=plan.getParent().resolve(entry.get("ruta").getAsString()).normalize().toRealPath();
  if(!OcrPagina.hash(p).equals(entry.get("sha256").getAsString()))throw new IllegalArgumentException("Entrada alterada: "+p);return p;
 }
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("Uso: FragmentarEstructurado <plan.json> <salida-nueva>");
  Path plan=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();if(Files.exists(out))throw new IllegalArgumentException("Salida existente");
  JsonObject config=read(plan);Map<String,Path> files=new HashMap<>();for(var e:config.getAsJsonObject("entradas").entrySet())files.put(e.getKey(),verified(plan,e.getValue().getAsJsonObject()));
  JsonObject annexReview=read(files.get("revision_anexo")),classification=read(files.get("relacion_documental"));
  boolean annexConfirmed=false;for(var r:classification.getAsJsonArray("relaciones_propuestas")){var rel=r.getAsJsonObject();if(rel.get("origen").getAsString().equals("doc-010")&&rel.get("destino").getAsString().equals("doc-009")&&rel.get("tipo").getAsString().equals("ANEXO_DE")&&rel.get("estado").getAsString().equals("CONFIGURADO_POR_USUARIO_PARA_PROTOTIPO"))annexConfirmed=true;}
  if(!annexConfirmed)throw new IllegalArgumentException("Relacion de anexo no configurada");
  Set<String> excluded=new HashSet<>();for(var p:annexReview.getAsJsonArray("pendientes")){var pending=p.getAsJsonObject();if(pending.has("unidad"))excluded.add(pending.get("unidad").getAsString());}
  Map<String,JsonObject> docs=new HashMap<>();for(var d:config.getAsJsonArray("documentos")){var doc=d.getAsJsonObject();if(docs.put(doc.get("documento_id").getAsString(),doc)!=null)throw new IllegalArgumentException("Documento duplicado");}
  if(!classification.get("sha256").getAsString().equals(OcrPagina.hash(files.get("pdf"))))throw new IllegalArgumentException("Clasificacion y PDF no coinciden");
  JsonObject annex=docs.get("doc-010");String confirmedMonth=null;
  for(var item:annexReview.getAsJsonArray("confirmaciones")){var confirmation=item.getAsJsonObject();if(confirmation.get("campo").getAsString().equals("fecha_anexo")){
   if(!confirmation.get("precision").getAsString().equals("MES")||!confirmation.get("fecha_dia").isJsonNull())throw new IllegalArgumentException("Precision de fecha no admitida");
   var format=new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("MMMM uuuu").toFormatter(Locale.forLanguageTag("es"));confirmedMonth=java.time.YearMonth.parse(confirmation.get("valor_literal").getAsString(),format).toString();
  }}
  if(annex==null||confirmedMonth==null||!annex.get("fecha_emision").isJsonNull()||!annex.get("precision_fecha").getAsString().equals("MES")||!annex.get("fecha_periodo").getAsString().equals(confirmedMonth)||!annex.get("clave").isJsonNull())throw new IllegalArgumentException("El anexo no puede heredar fecha diaria ni clave de circular");
  StringBuilder all=new StringBuilder(),candidates=new StringBuilder(),blocked=new StringBuilder();Set<String> seen=new HashSet<>(),foundExcluded=new HashSet<>();JsonArray graph=read(files.get("estructura_circular")).getAsJsonArray("relaciones");
  int count=0,eligible=0;Map<String,Integer> counts=new TreeMap<>();List<JsonObject> results=new ArrayList<>();
  for(String line:Files.readAllLines(files.get("unidades"),StandardCharsets.UTF_8))if(!line.isBlank()){
   JsonObject unit=JsonParser.parseString(line).getAsJsonObject();String uid=unit.get("id").getAsString(),docId=unit.get("documento_id").getAsString();int page=unit.get("pagina").getAsInt();
   if(!seen.add(uid)||!docs.containsKey(docId))throw new IllegalArgumentException("Unidad duplicada o documento desconocido");
   JsonObject doc=docs.get(docId);boolean pageAllowed=false;for(var n:doc.getAsJsonArray("paginas"))if(n.getAsInt()==page)pageAllowed=true;
   if(!pageAllowed)throw new IllegalArgumentException("Pagina ajena al documento");
   String literal=unit.has("texto_literal_propuesto")?unit.get("texto_literal_propuesto").getAsString():unit.get("texto_propuesto").getAsString();
   if(literal.isBlank()||literal.length()>config.get("max_caracteres_utf16").getAsInt())throw new IllegalArgumentException("Unidad requiere subdivision revisada");
   JsonArray refs=new JsonArray();
   for(var r:unit.getAsJsonArray("fuentes")){
    JsonObject ref=r.getAsJsonObject();Path file=files.get("unidades").getParent().resolve(ref.get("archivo_ocr").getAsString()).normalize().toRealPath();JsonObject matching=null;
    for(String row:Files.readAllLines(file,StandardCharsets.UTF_8))if(!row.isBlank()){var parsed=JsonParser.parseString(row).getAsJsonObject();if(parsed.get("id").equals(ref.get("grupo")))matching=parsed;}
    if(matching==null||matching.get("pagina").getAsInt()!=page||!matching.get("bbox_px").equals(ref.get("bbox_pagina_px")))throw new IllegalArgumentException("Referencia OCR no coincide");
    JsonObject checked=ref.deepCopy();checked.addProperty("archivo_ocr_absoluto",file.toString());checked.addProperty("archivo_ocr_sha256",OcrPagina.hash(file));refs.add(checked);
   }
   if(refs.isEmpty())throw new IllegalArgumentException("Unidad sin procedencia");
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(literal.getBytes(StandardCharsets.UTF_8)));
   JsonObject fragment=new JsonObject();fragment.addProperty("id",docId+"-"+uid+"-"+hash);fragment.addProperty("unidad_id",uid);fragment.add("documento",doc);fragment.addProperty("pagina",page);fragment.add("seccion",unit.get("seccion"));fragment.add("numero",unit.get("numero"));fragment.addProperty("texto_fuente_propuesto",literal);
   fragment.addProperty("texto_sha256",hash);fragment.addProperty("estado_texto","TRANSCRIPCION_PROPUESTA_CON_DATOS_CRITICOS_REVISADOS");fragment.addProperty("cita_literal_pdf_certificada",false);fragment.addProperty("fuente_pdf",files.get("pdf").toString());fragment.addProperty("fuente_pdf_sha256",OcrPagina.hash(files.get("pdf")));fragment.add("procedencia_ocr",refs);
   if(unit.has("texto_editorial_autorizado")){fragment.add("texto_editorial_autorizado",unit.get("texto_editorial_autorizado"));fragment.addProperty("editorial_es_cita_literal",false);}
   if(unit.has("validaciones_datos_criticos"))fragment.add("validaciones_datos_criticos",unit.get("validaciones_datos_criticos"));
   boolean candidate=!excluded.contains(uid);if(!candidate)foundExcluded.add(uid);fragment.addProperty("candidato_para_revision",candidate);fragment.addProperty("habilitado_para_rag",false);fragment.addProperty("motivo_estado",candidate?"Preparado para siguiente revision e ingesta; no supone aprobacion integral":"Unidad pendiente por indicacion del usuario; excluir tambien de expansion de contexto");
   JsonArray dependencies=new JsonArray();for(var edge:graph){var e=edge.getAsJsonObject();if(e.get("destino").getAsString().equals(uid)||e.get("origen").getAsString().equals(uid))dependencies.add(e);}
   fragment.add("relaciones_contexto",dependencies);fragment.add("pendientes_documentales",annexReview.get("pendientes"));String serialized=JSON.toJson(fragment)+"\n";all.append(serialized);if(candidate){candidates.append(serialized);eligible++;}else blocked.append(serialized);count++;counts.merge(docId,1,Integer::sum);results.add(fragment);
  }
  if(!foundExcluded.equals(excluded))throw new IllegalArgumentException("Exclusion pendiente no encontrada");
  for(var edge:graph){var e=edge.getAsJsonObject();if(!seen.contains(e.get("origen").getAsString())||!seen.contains(e.get("destino").getAsString()))throw new IllegalArgumentException("Relacion huerfana");}
  Files.createDirectories(out);Files.writeString(out.resolve("fragmentos-todos.jsonl"),all,StandardCharsets.UTF_8);Files.writeString(out.resolve("fragmentos-candidatos.jsonl"),candidates,StandardCharsets.UTF_8);Files.writeString(out.resolve("fragmentos-excluidos.jsonl"),blocked,StandardCharsets.UTF_8);
  JsonObject report=new JsonObject();report.addProperty("plan_sha256",OcrPagina.hash(plan));report.addProperty("total",count);report.addProperty("candidatos_para_revision",eligible);report.addProperty("excluidos",count-eligible);report.addProperty("indexado",false);report.add("por_documento",JSON.toJsonTree(counts));report.add("relaciones_contexto",graph);report.addProperty("limite","Candidatos estructurados, no transcripciones integrales aprobadas ni citas PDF certificadas");
  Files.writeString(out.resolve("ejecucion.json"),JSON.toJson(report)+"\n",StandardCharsets.UTF_8);
  StringBuilder readable=new StringBuilder("# Fragmentos estructurados: circular y anexo\n\nCandidatos de revision, no indexados. La pagina 4 permanece separada.\n\n");
  for(JsonObject f:results){readable.append("## ").append(f.get("unidad_id").getAsString()).append(" · pagina ").append(f.get("pagina").getAsInt()).append(f.get("candidato_para_revision").getAsBoolean()?"":" · EXCLUIDO").append("\n\n").append(f.get("seccion").getAsString()).append("\n\n").append(f.get("texto_fuente_propuesto").getAsString()).append("\n\n");if(f.has("texto_editorial_autorizado"))readable.append("Version editorial autorizada (no cita literal): ").append(f.get("texto_editorial_autorizado").getAsString()).append("\n\n");}
  Files.writeString(out.resolve("fragmentos-lectura.md"),readable,StandardCharsets.UTF_8);System.out.println("Fragmentos: "+count+"; candidatos "+eligible+"; excluidos "+(count-eligible)+". Sin indexar.");
 }
}
