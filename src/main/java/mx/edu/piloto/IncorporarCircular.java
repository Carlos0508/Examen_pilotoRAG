package mx.edu.piloto;
import com.google.gson.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import java.nio.file.*;
import java.nio.channels.*;
import java.text.Normalizer;
import java.util.*;
import javax.imageio.ImageIO;

/** Altas incrementales: procesamiento local, vectorización y publicación atómica. */
public final class IncorporarCircular {
 static final long MAX_BYTES=30L*1024*1024;
 static JsonObject ref(Path p)throws Exception{var o=new JsonObject();o.addProperty("ruta",p.toRealPath().toString());o.addProperty("sha256",OcrPagina.hash(p));return o;}
 static String clean(String s){return Normalizer.normalize(s,Normalizer.Form.NFC).replace("\u00ad","").replace("\r\n","\n").replace('\r','\n').replaceAll("[\\p{Cc}&&[^\\n\\t]]"," ");}
 static boolean adequate(String s){long letters=s.codePoints().filter(Character::isLetterOrDigit).count();return letters>=40&&s.chars().filter(c->c==0xfffd).count()<Math.max(1,s.length()/100);}
 static List<Path> pending(Path root)throws Exception{
  Path inbox=root.resolve("entrada-circulares");Files.createDirectories(inbox);
  try(var files=Files.list(inbox)){return files.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")).sorted().toList();}
 }
 static void archived(Path root,Path source)throws Exception{
  Path inbox=root.resolve("entrada-circulares").toRealPath();if(!source.toRealPath().getParent().equals(inbox))throw new IllegalArgumentException("Archivo fuera de entrada");
  Path done=inbox.resolve("procesados");Files.createDirectories(done);Path target=done.resolve(source.getFileName());if(Files.exists(target))target=done.resolve(UUID.randomUUID()+"-"+source.getFileName());Files.move(source,target);
 }
 static JsonObject ingest(Path root,Path input,String proposed,Path progress)throws Exception{
  Path inbox=root.resolve("entrada-circulares").toRealPath();if(Files.isSymbolicLink(input)||!input.toRealPath().getParent().equals(inbox))throw new IllegalArgumentException("Solo PDF de la carpeta de entrada");
  if(Files.size(input)>MAX_BYTES||Files.size(input)<5)throw new IllegalArgumentException("PDF vacío o superior a 30 MB");
  try(var in=Files.newInputStream(input)){if(!new String(in.readNBytes(5),java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw new IllegalArgumentException("El archivo no tiene cabecera PDF");}
  Path active=RegistroAcervo.active(root);var oldIndex=CorpusMultidocumento.read(active);CorpusMultidocumento.verifyIndex(oldIndex);
  String sha=OcrPagina.hash(input);Set<String> known=new HashSet<>();
  var base=CorpusMultidocumento.read(root.resolve("corpus/acervo-completo-01/catalogo-cobertura.json"));for(var e:base.getAsJsonArray("fuentes"))known.add(e.getAsJsonObject().get("sha256").getAsString());
  for(var c:RegistroAcervo.additions(oldIndex))known.add(c.getAsJsonObject("pdf").get("sha256").getAsString());
  var result=new JsonObject();result.addProperty("archivo",input.getFileName().toString());
  if(known.contains(sha)){result.addProperty("estado","DUPLICADA");result.addProperty("detalle","El mismo PDF ya está registrado; no se modificó el índice");archived(root,input);return result;}
  String id="alta-"+UUID.randomUUID().toString().replace("-","");Path dir=root.resolve("altas").resolve(id);Files.createDirectories(dir);Path pdf=dir.resolve("fuente.pdf");Files.copy(input,pdf);
  String name=input.getFileName().toString();JsonArray pages=new JsonArray(),chunks=new JsonArray();String key=proposed;int order=0;
  try(var doc=Loader.loadPDF(pdf.toFile())){
   if(doc.isEncrypted())throw new IllegalArgumentException("El PDF está protegido; utiliza una copia sin contraseña");
   if(doc.getNumberOfPages()<1||doc.getNumberOfPages()>100)throw new IllegalArgumentException("Se admiten de 1 a 100 páginas por PDF");
   PDFRenderer renderer=new PDFRenderer(doc);PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
   for(int i=0;i<doc.getNumberOfPages();i++){
    var state=new JsonObject();state.addProperty("estado","PROCESANDO");state.addProperty("detalle",name+": página "+(i+1)+" de "+doc.getNumberOfPages());RegistroAcervo.atomic(progress,state);
    stripper.setStartPage(i+1);stripper.setEndPage(i+1);String nativeText=stripper.getText(doc);Files.writeString(dir.resolve("p%03d-nativo.txt".formatted(i+1)),nativeText);
    String text=clean(nativeText),method="TEXTO_PDF";var page=new JsonObject();page.addProperty("pagina",i+1);
    if(!adequate(text)){
     var box=doc.getPage(i).getCropBox();if(box.getWidth()*box.getHeight()*4>25_000_000||box.getWidth()>5000||box.getHeight()>5000)throw new IllegalArgumentException("Página demasiado grande para el OCR local");
     Path image=dir.resolve("p%03d.png".formatted(i+1));ImageIO.write(renderer.renderImageWithDPI(i,144),"png",image.toFile());page.add("imagen",ref(image));
     Path exe=Path.of("C:/Program Files/Tesseract-OCR/tesseract.exe"),stem=dir.resolve("p%03d-ocr".formatted(i+1));
     if(!Files.isRegularFile(exe))throw new IllegalArgumentException("No se encontró Tesseract en la ruta configurada");
     OcrPagina.execute(List.of(exe.toString(),image.toString(),stem.toString(),"--tessdata-dir",exe.getParent().resolve("tessdata").toString(),"-l","spa","--psm","3","txt","tsv"),dir.resolve("p%03d-ocr.log".formatted(i+1)));
     Path raw=Path.of(stem+".txt");text=clean(Files.readString(raw));page.add("ocr",ref(raw));method="TESSERACT_SPA_PSM3";
    }
    if(i==0&&(key==null||key.isBlank())){var found=ClavesDocumentales.canonicas(text.substring(0,Math.min(700,text.length())));if(found.size()==1)key=found.iterator().next();}
    Path textPath=dir.resolve("p%03d-texto.txt".formatted(i+1));Files.writeString(textPath,text);page.add("texto",ref(textPath));page.addProperty("metodo",method);page.addProperty("sin_texto",text.isBlank());pages.add(page);
    int start=0;while(start<text.length()){
     while(start<text.length()&&Character.isWhitespace(text.charAt(start)))start++;if(start==text.length())break;
     int end=Math.min(text.length(),start+1400);if(end<text.length()){int cut=end;while(cut>start+800&&!Character.isWhitespace(text.charAt(cut-1)))cut--;if(cut>start+800)end=cut;else if(Character.isHighSurrogate(text.charAt(end-1)))end--;}
     var c=new JsonObject();c.addProperty("id",id+"-"+(++order));c.addProperty("documento_id",id);c.addProperty("fuente_id",id);c.addProperty("orden",order);c.addProperty("unidad_id","página "+(i+1)+", bloque automático "+order);c.addProperty("seccion","PAGINA_%03d".formatted(i+1));c.addProperty("pagina",i+1);c.add("paginas",CorpusMultidocumento.JSON.toJsonTree(List.of(i+1)));c.addProperty("texto",text.substring(start,end));
     c.addProperty("fuente_pdf",pdf.toString());c.addProperty("fuente_pdf_sha256",sha);c.addProperty("fuente_nombre",name);c.addProperty("fuente_texto",textPath.toString());c.addProperty("fuente_texto_sha256",OcrPagina.hash(textPath));c.addProperty("inicio_utf16",start);c.addProperty("fin_utf16_exclusivo",end);
     c.addProperty("nivel_revision","OCR_AUTOMATICO_NO_REVISADO");c.addProperty("contenido_confirmado_por_usuario",false);c.addProperty("metadatos_confirmados_por_usuario",false);c.addProperty("tipo_documento","DOCUMENTO_CARGADO_NO_CLASIFICADO");c.addProperty("politica_recuperacion","GENERAL_PILOTO_NO_REVISADO");c.addProperty("vigencia","NO_DETERMINADA");c.addProperty("metodo_extraccion",method);
     for(String field:List.of("fecha_emision","asunto","remitente_nombre"))c.add(field,JsonNull.INSTANCE);chunks.add(c);start=end;
    }
   }
  }
  if(chunks.isEmpty())throw new IllegalArgumentException("No se pudo extraer texto. Revisa el PDF; no se modificó el índice");
  for(var e:chunks){var c=e.getAsJsonObject();c.add("clave",key==null?JsonNull.INSTANCE:new JsonPrimitive(key));c.addProperty("clave_cita",(key==null?"Sin clave verificada":key)+" | "+name);}
  Path chunkPath=dir.resolve("fragmentos.jsonl");StringBuilder lines=new StringBuilder();for(var e:chunks)lines.append(CorpusMultidocumento.JSON.toJson(e)).append('\n');Files.writeString(chunkPath,lines);
  var config=new JsonObject();config.addProperty("modo","ALTA_LOCAL_AUTOMATICA_NO_REVISADA");config.addProperty("fuente_id",id);config.addProperty("archivo",name);config.add("clave_propuesta",key==null?JsonNull.INSTANCE:new JsonPrimitive(key));config.add("pdf",ref(pdf));config.add("fragmentos",ref(chunkPath));config.addProperty("paginas_total",pages.size());config.add("paginas",pages);config.addProperty("creado",java.time.Instant.now().toString());config.addProperty("autorizacion","Carga o procesamiento solicitado desde la interfaz local; no confirma el texto ni su vigencia");
  Path cfg=dir.resolve("config.json");BusquedaLocal.write(cfg,config);CorpusAltas.load(cfg);
  var vectorState=new JsonObject();vectorState.addProperty("estado","PROCESANDO");vectorState.addProperty("detalle",name+": generando embeddings y verificando el índice");RegistroAcervo.atomic(progress,vectorState);
  String digest=BusquedaLocal.digest();if(!digest.equals(oldIndex.get("modelo_digest").getAsString()))throw new IllegalArgumentException("Cambió el modelo de embeddings; no se activó el documento");
  var entries=oldIndex.getAsJsonArray("entradas").deepCopy();for(int start=0;start<chunks.size();start+=8){List<String> texts=new ArrayList<>();for(int j=start;j<Math.min(start+8,chunks.size());j++)texts.add(chunks.get(j).getAsJsonObject().get("texto").getAsString());var vectors=BusquedaLocal.embed(texts);for(int j=0;j<texts.size();j++){var entry=new JsonObject();entry.add("fragmento",chunks.get(start+j));entry.add("vector",CorpusMultidocumento.JSON.toJsonTree(vectors.get(j)));entries.add(entry);}}
  if(!digest.equals(BusquedaLocal.digest()))throw new IllegalArgumentException("Cambió el modelo durante el alta");
  var manifest=CorpusMultidocumento.read(Path.of(oldIndex.get("manifiesto").getAsString()));
  // Las referencias heredadas pasan a absolutas antes de cambiar de directorio.
  absolutize(manifest,Path.of(oldIndex.get("manifiesto").getAsString()).getParent());
  if(!manifest.has("altas"))manifest.add("altas",new JsonArray());manifest.getAsJsonArray("altas").add(ref(cfg));Path mp=dir.resolve("manifiesto.json");BusquedaLocal.write(mp,manifest);
  var index=oldIndex.deepCopy();index.addProperty("manifiesto",mp.toString());index.addProperty("manifiesto_sha256",OcrPagina.hash(mp));index.add("entradas",entries);CorpusMultidocumento.verifyIndex(index);Path ip=dir.resolve("indice.json");BusquedaLocal.write(ip,index);
  RegistroAcervo.activate(root,ip);result.addProperty("estado","DISPONIBLE_PARA_CONSULTA");result.addProperty("fragmentos",chunks.size());result.addProperty("fuente_id",id);result.add("clave_propuesta",config.get("clave_propuesta"));result.addProperty("revision","TRANSCRIPCION_AUTOMATICA_NO_REVISADA");
  long noText=0;for(var page:pages)if(page.getAsJsonObject().get("sin_texto").getAsBoolean())noText++;if(noText>0)result.addProperty("aviso",noText+" páginas sin texto extraíble; revisa el PDF. Esas páginas no se consultan mediante RAG");
  try{archived(root,input);}catch(Exception e){result.addProperty("aviso","Ya incorporada; no se pudo mover la copia de entrada a procesados");}return result;
 }
 static void absolutize(JsonElement element,Path parent){if(element.isJsonObject()){var o=element.getAsJsonObject();if(o.has("ruta")&&o.has("sha256"))o.addProperty("ruta",parent.resolve(o.get("ruta").getAsString()).normalize().toString());for(var e:o.entrySet())absolutize(e.getValue(),parent);}else if(element.isJsonArray())for(var e:element.getAsJsonArray())absolutize(e,parent);}
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[0]).toRealPath(),progress=Path.of(args[1]);String filename=args.length>2?args[2]:"",key=args.length>3?args[3]:"";
  Files.createDirectories(root.resolve("altas"));
  try(var channel=FileChannel.open(root.resolve("altas/incorporacion.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()){
   if(lock==null)throw new IllegalStateException("Otra incorporación está en curso");
   List<Path> candidates=filename.isEmpty()?pending(root):List.of(root.resolve("entrada-circulares").resolve(filename));JsonArray results=new JsonArray();
   if(candidates.size()>20)candidates=candidates.subList(0,20);
   for(Path input:candidates){try{results.add(ingest(root,input,key.isBlank()?null:key,progress));}catch(Exception e){var r=new JsonObject();r.addProperty("archivo",input.getFileName().toString());r.addProperty("estado","ERROR");r.addProperty("detalle",e.getMessage());results.add(r);e.printStackTrace();}}
   var done=new JsonObject();done.addProperty("estado","TERMINADO");done.add("resultados",results);RegistroAcervo.atomic(progress,done);System.out.println(done);
  }
 }
}
