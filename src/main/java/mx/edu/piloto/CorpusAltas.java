package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Verifica que una alta conserve PDF, páginas y fragmentos sin atribuir revisión humana. */
public final class CorpusAltas {
 static List<JsonObject> load(Path config)throws Exception{
  var cfg=CorpusMultidocumento.read(config);Path dir=config.toRealPath().getParent();
  if(!cfg.get("modo").getAsString().equals("ALTA_LOCAL_AUTOMATICA_NO_REVISADA"))throw new IllegalArgumentException("Alta inválida");
  Path pdf=CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("pdf"));
  try(var document=org.apache.pdfbox.Loader.loadPDF(pdf.toFile())){if(document.getNumberOfPages()!=cfg.get("paginas_total").getAsInt())throw new IllegalArgumentException("El número de páginas no coincide con el PDF");}
  var chunks=CorpusMultidocumento.rows(CorpusMultidocumento.verified(dir,cfg.getAsJsonObject("fragmentos")));
  Map<Integer,String> texts=new HashMap<>();Map<Integer,JsonObject> pageRecords=new HashMap<>();int expectedPage=0;
  for(var e:cfg.getAsJsonArray("paginas")){var p=e.getAsJsonObject();int page=p.get("pagina").getAsInt();if(page!=++expectedPage)throw new IllegalArgumentException("Páginas discontinuas");
   Path text=CorpusMultidocumento.verified(dir,p.getAsJsonObject("texto"));texts.put(page,Files.readString(text));pageRecords.put(page,p);
   if(p.has("imagen"))CorpusMultidocumento.verified(dir,p.getAsJsonObject("imagen"));
   if(p.has("ocr"))CorpusMultidocumento.verified(dir,p.getAsJsonObject("ocr"));
   if(texts.get(page).isBlank()!=p.get("sin_texto").getAsBoolean())throw new IllegalArgumentException("Estado de página inválido");
  }
  if(expectedPage!=cfg.get("paginas_total").getAsInt()||expectedPage<1)throw new IllegalArgumentException("Páginas incompletas");
  Map<Integer,Integer> ends=new HashMap<>();Set<String> ids=new HashSet<>();int order=0;
  for(var c:chunks){int page=c.get("pagina").getAsInt();String text=texts.get(page);int a=c.get("inicio_utf16").getAsInt(),z=c.get("fin_utf16_exclusivo").getAsInt();
   if(text==null||a<ends.getOrDefault(page,0)||z<=a||z>text.length()||!text.substring(ends.getOrDefault(page,0),a).isBlank()||!text.substring(a,z).equals(c.get("texto").getAsString())||z-a>1400)throw new IllegalArgumentException("Fragmento alterado");ends.put(page,z);
   if(!ids.add(c.get("id").getAsString())||c.get("orden").getAsInt()!=++order||!c.get("documento_id").equals(cfg.get("fuente_id"))||!c.get("fuente_id").equals(cfg.get("fuente_id"))||!c.get("fuente_nombre").equals(cfg.get("archivo"))||!Path.of(c.get("fuente_pdf").getAsString()).toRealPath().equals(pdf)||!c.get("fuente_pdf_sha256").equals(cfg.getAsJsonObject("pdf").get("sha256"))||!c.get("clave").equals(cfg.get("clave_propuesta")))throw new IllegalArgumentException("Identidad de alta alterada");
   Path textPath=dir.resolve(pageRecords.get(page).getAsJsonObject("texto").get("ruta").getAsString()).toRealPath();
   if(!Path.of(c.get("fuente_texto").getAsString()).toRealPath().equals(textPath)||!c.get("fuente_texto_sha256").equals(pageRecords.get(page).getAsJsonObject("texto").get("sha256")))throw new IllegalArgumentException("Texto sin trazabilidad");
   if(!c.get("nivel_revision").getAsString().equals("OCR_AUTOMATICO_NO_REVISADO")||c.get("contenido_confirmado_por_usuario").getAsBoolean()||c.get("metadatos_confirmados_por_usuario").getAsBoolean()||!c.get("fecha_emision").isJsonNull()||!c.get("asunto").isJsonNull()||!c.get("remitente_nombre").isJsonNull()||!c.get("vigencia").getAsString().equals("NO_DETERMINADA")||!c.get("politica_recuperacion").getAsString().equals("GENERAL_PILOTO_NO_REVISADO")||!c.get("tipo_documento").getAsString().equals("DOCUMENTO_CARGADO_NO_CLASIFICADO")||!c.get("paginas").equals(CorpusMultidocumento.JSON.toJsonTree(List.of(page))))throw new IllegalArgumentException("Revisión inventada o metadatos no admitidos");
  }
  for(var e:texts.entrySet())if(!e.getValue().substring(ends.getOrDefault(e.getKey(),0)).isBlank())throw new IllegalArgumentException("Texto omitido");
  if(chunks.isEmpty())throw new IllegalArgumentException("No hay texto consultable");return chunks;
 }
}
