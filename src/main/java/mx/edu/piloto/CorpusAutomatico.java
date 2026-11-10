package mx.edu.piloto;
import com.google.gson.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Fuentes masivas no revisadas: autorización de procesamiento nunca equivale a aprobación de OCR. */
public final class CorpusAutomatico{
 static List<JsonObject> load(Path config)throws Exception{
  var cfg=CorpusMultidocumento.read(config);if(!cfg.get("formato").getAsString().equals("corpus-automatico-v1"))throw new IllegalArgumentException("Formato automático no admitido");Path base=config.getParent();var auth=CorpusMultidocumento.read(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("autorizacion")));
  if(!auth.get("modo").getAsString().equals("AMPLIACION_MASIVA_AUTORIZADA_NO_CONFIRMACION_OCR")||auth.get("contenido_confirmado_por_usuario").getAsBoolean())throw new IllegalArgumentException("Autorización no equivale a confirmación de texto");
  if(!OcrPagina.hash(Path.of(auth.get("indice_base").getAsString())).equals(auth.get("indice_base_sha256").getAsString()))throw new IllegalArgumentException("Índice base alterado");
  var catalog=CorpusMultidocumento.read(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("catalogo")));Map<String,JsonObject> files=new HashMap<>();for(var f:catalog.getAsJsonArray("fuentes"))files.put(f.getAsJsonObject().get("fuente_id").getAsString(),f.getAsJsonObject());
  Map<String,JsonObject> catalogPages=new LinkedHashMap<>();for(var e:files.entrySet())for(var p:e.getValue().getAsJsonArray("paginas")){var page=p.getAsJsonObject();if(page.get("estado").getAsString().equals("INDEXABLE_AUTOMATICO_NO_REVISADO"))catalogPages.put(e.getKey()+":"+page.get("pagina").getAsInt(),page);}
  Map<String,JsonObject> pages=new LinkedHashMap<>();Map<Path,String> hashes=new HashMap<>();Map<String,String> texts=new HashMap<>();
  for(var p:CorpusMultidocumento.rows(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("paginas")))){
   String key=p.get("fuente_id").getAsString()+":"+p.get("pagina").getAsInt();if(pages.putIfAbsent(key,p)!=null||p.get("confirmado_por_usuario").getAsBoolean())throw new IllegalArgumentException("Página duplicada o confirmación inventada");
   var expectedPage=catalogPages.get(key);if(expectedPage==null||!p.get("archivo_texto").equals(expectedPage.get("archivo_texto"))||!p.get("texto_sha256").equals(expectedPage.get("sha256_texto"))||!p.get("documento_id").equals(expectedPage.get("documento_id"))||!p.get("politica_recuperacion").equals(expectedPage.get("politica_recuperacion")))throw new IllegalArgumentException("Página no corresponde con cobertura declarada en catálogo");
   verify(Path.of(p.get("archivo_texto").getAsString()),p.get("texto_sha256").getAsString(),hashes);verify(Path.of(p.get("imagen").getAsString()),p.get("imagen_sha256").getAsString(),hashes);verify(Path.of(p.get("origen_texto").getAsString()),p.get("origen_sha256").getAsString(),hashes);
   texts.put(key,Files.readString(Path.of(p.get("archivo_texto").getAsString()),StandardCharsets.UTF_8).replace("\r\n","\n").replace("\r","\n"));
  }
  if(!pages.keySet().equals(catalogPages.keySet()))throw new IllegalArgumentException("Catálogo contiene páginas indexables omitidas");
  var chunks=CorpusMultidocumento.rows(CorpusMultidocumento.verified(base,cfg.getAsJsonObject("fragmentos")));Map<String,TreeMap<Integer,Integer>> spans=new HashMap<>();Set<String> ids=new HashSet<>();Map<String,Integer> order=new HashMap<>();
  Set<String> policies=Set.of("GENERAL_PILOTO_NO_REVISADO","ARCHIVO_FAX_EXPLICITO","ARCHIVO_OFICIO_EXPLICITO","VERSION_TRABAJO_EXPLICITA");
  for(var c:chunks){String fid=c.get("fuente_id").getAsString(),page=fid+":"+c.get("pagina").getAsInt();var p=pages.get(page);var f=files.get(fid);String doc=c.get("documento_id").getAsString();
   if(p==null||f==null||!ids.add(c.get("id").getAsString())||!c.get("nivel_revision").getAsString().equals("OCR_AUTOMATICO_NO_REVISADO")||c.get("contenido_confirmado_por_usuario").getAsBoolean()||c.get("metadatos_confirmados_por_usuario").getAsBoolean()||!c.get("vigencia").getAsString().equals("NO_DETERMINADA")||!c.get("fecha_emision").isJsonNull()||!c.get("asunto").isJsonNull()||!c.get("remitente_nombre").isJsonNull()||!policies.contains(c.get("politica_recuperacion").getAsString()))throw new IllegalArgumentException("Estado de revisión automático inválido");
   if(!c.get("fuente_pdf").equals(f.get("fuente_pdf"))||!c.get("fuente_pdf_sha256").equals(f.get("sha256"))||!c.get("fuente_texto").equals(p.get("archivo_texto"))||!c.get("fuente_texto_sha256").equals(p.get("texto_sha256"))||!c.get("tipo_documento").equals(p.get("tipo_documento"))||!c.get("documento_id").equals(p.get("documento_id"))||!c.get("politica_recuperacion").equals(p.get("politica_recuperacion")))throw new IllegalArgumentException("Fuente o política sin trazabilidad");
   verify(Path.of(c.get("fuente_pdf").getAsString()),c.get("fuente_pdf_sha256").getAsString(),hashes);
   if(!c.get("clave").isJsonNull()&&!c.get("clave").equals(f.get("clave_propuesta")))throw new IllegalArgumentException("Clave no coincide con encabezado propuesto");
   if(c.has("clave_padre")&&!c.get("clave_padre").equals(f.get("clave_propuesta")))throw new IllegalArgumentException("Relación de anexo sin trazabilidad");
   int next=order.getOrDefault(doc,0)+1;order.put(doc,next);if(c.get("orden").getAsInt()!=next)throw new IllegalArgumentException("Orden discontinuo");
   String text=texts.get(page);int a=c.get("inicio_utf16").getAsInt(),z=c.get("fin_utf16_exclusivo").getAsInt();if(a<0||z<=a||z>text.length()||!CorpusMultipagina.compact(text.substring(a,z)).equals(c.get("texto").getAsString())||c.get("texto").getAsString().length()>cfg.get("max_caracteres").getAsInt()||!c.get("paginas").equals(CorpusMultidocumento.JSON.toJsonTree(List.of(c.get("pagina").getAsInt()))))throw new IllegalArgumentException("Fragmento automático alterado");
   if(spans.computeIfAbsent(page,k->new TreeMap<>()).putIfAbsent(a,z)!=null)throw new IllegalArgumentException("Rango duplicado");
  }
  if(!spans.keySet().equals(pages.keySet()))throw new IllegalArgumentException("Páginas automáticas sin cobertura");
  for(var e:spans.entrySet()){String text=texts.get(e.getKey());int end=0;for(var s:e.getValue().entrySet()){if(s.getKey()<end||!text.substring(end,s.getKey()).isBlank())throw new IllegalArgumentException("Solapamiento u omisión en página");end=s.getValue();}if(!text.substring(end).isBlank())throw new IllegalArgumentException("Cola de página omitida");}
  return chunks;
 }
 static void verify(Path p,String hash,Map<Path,String> cache)throws Exception{Path real=p.toRealPath();String actual=cache.get(real);if(actual==null){actual=OcrPagina.hash(real);cache.put(real,actual);}if(!actual.equals(hash))throw new IllegalArgumentException("Fuente alterada: "+real);}
 static boolean eligible(JsonObject c,String question){
  if(!c.has("politica_recuperacion"))return true;String q=ControlEvidencia.normalized(question);return switch(c.get("politica_recuperacion").getAsString()){
   case "ARCHIVO_FAX_EXPLICITO"->q.contains("fax")||q.contains("cubierta");case "ARCHIVO_OFICIO_EXPLICITO"->q.contains("oficio")||q.contains("envio de documentacion");case "VERSION_TRABAJO_EXPLICITA"->q.contains("version de trabajo")||q.contains("borrador")||q.contains("tachadura");default->true;
  };
 }
}
