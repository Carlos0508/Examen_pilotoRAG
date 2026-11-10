package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Valida un manifiesto de varios documentos sin cargar modelos ni abrir conexiones. */
public final class ValidarCorpus {
 static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
 static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p,StandardCharsets.UTF_8)).getAsJsonObject();}
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("Uso: ValidarCorpus <manifiesto.json> <resultado-nuevo.json>");
  Path manifest=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();if(Files.exists(out))throw new IllegalArgumentException("Salida existente");
  JsonObject report=validate(manifest);Files.writeString(out,JSON.toJson(report)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);System.out.println("Corpus validado. Sin indexacion.");
 }
 static JsonObject validate(Path manifest)throws Exception{
  JsonObject m=read(manifest);Set<String> docs=new HashSet<>(),ids=new HashSet<>();JsonArray result=new JsonArray();int count=0;
  for(var entry:m.getAsJsonArray("documentos")){
   JsonObject d=entry.getAsJsonObject();String id=d.get("documento_id").getAsString();if(!docs.add(id))throw new IllegalArgumentException("Documento repetido");
   Path chunks=manifest.getParent().resolve(d.get("fragmentos").getAsString()).normalize().toRealPath(),confirmation=manifest.getParent().resolve(d.get("confirmacion").getAsString()).normalize().toRealPath();
   if(!OcrPagina.hash(chunks).equals(d.get("fragmentos_sha256").getAsString())||!OcrPagina.hash(confirmation).equals(d.get("confirmacion_sha256").getAsString()))throw new IllegalArgumentException("Manifiesto no coincide con sus fuentes");
   JsonObject a=read(confirmation);String mode=d.get("modo_validacion").getAsString();Set<String> expected=new HashSet<>(),seen=new HashSet<>();
   if(mode.equals("FRAGMENTOS_CONFIRMADOS")){
    if(!a.get("estado").getAsString().equals("FRAGMENTACION_CONFIRMADA_POR_USUARIO")||!a.get("fragmentos_sha256").equals(d.get("fragmentos_sha256")))throw new IllegalArgumentException("Confirmacion de fragmentos invalida");
    for(var e:a.getAsJsonArray("fragmentos_ids"))expected.add(e.getAsString());
   }else if(mode.equals("CUERPO_CONFIRMADO")){
    if(!a.get("estado").getAsString().equals("CUERPO_Y_DATOS_CONFIRMADOS_POR_USUARIO")||!a.get("documento_id").getAsString().equals(id))throw new IllegalArgumentException("Confirmacion de cuerpo invalida");
   }else throw new IllegalArgumentException("Modo no admitido");
   int n=0;List<JsonObject> ordered=new ArrayList<>();
   for(String line:Files.readAllLines(chunks,StandardCharsets.UTF_8))if(!line.isBlank()){
    JsonObject c=JsonParser.parseString(line).getAsJsonObject();String chunkId=c.get("id").getAsString();
    if(!c.get("documento_id").getAsString().equals(id)||!ids.add(chunkId)||!seen.add(chunkId))throw new IllegalArgumentException("Fragmento duplicado o de otro documento");
    Path source=Path.of(c.get("fuente_texto").getAsString()),pdf=Path.of(c.get("fuente_pdf").getAsString());String sourceText=Files.readString(source,StandardCharsets.UTF_8);
    if(!OcrPagina.hash(source).equals(c.get("fuente_texto_sha256").getAsString())||!OcrPagina.hash(pdf).equals(c.get("fuente_pdf_sha256").getAsString())||!sourceText.substring(c.get("inicio_utf16").getAsInt(),c.get("fin_utf16_exclusivo").getAsInt()).equals(c.get("texto").getAsString()))throw new IllegalArgumentException("Texto no literal o fuente alterada");
    if(mode.equals("CUERPO_CONFIRMADO")){
     if(!c.get("fuente_texto_sha256").equals(a.get("fuente_texto_sha256"))||!c.get("fuente_pdf_sha256").equals(a.get("fuente_pdf_sha256"))||c.get("pagina").getAsInt()!=a.get("pagina").getAsInt()||!c.get("confirmacion_sha256").getAsString().equals(OcrPagina.hash(confirmation)))throw new IllegalArgumentException("Fuente no coincide con cuerpo confirmado");
     for(var field:a.getAsJsonObject("metadatos_confirmados").entrySet())if(!field.getValue().equals(c.get(field.getKey())))throw new IllegalArgumentException("Metadato cambiado: "+field.getKey());
     for(String field:List.of("expediente_id","remitente_cargo","vigencia"))if(!a.get(field).equals(c.get(field)))throw new IllegalArgumentException("Metadato no confirmado: "+field);
    }
    ordered.add(c);n++;count++;
   }
   if(n==0||mode.equals("FRAGMENTOS_CONFIRMADOS")&&!seen.equals(expected))throw new IllegalArgumentException("Conjunto incompleto");
   if(mode.equals("CUERPO_CONFIRMADO")){
    Path body=confirmation.getParent().resolve(a.get("fuente_texto").getAsString()).normalize().toRealPath();String bodyText=Files.readString(body,StandardCharsets.UTF_8);int cursor=0;
    for(JsonObject c:ordered){int start=c.get("inicio_utf16").getAsInt(),end=c.get("fin_utf16_exclusivo").getAsInt();if(start<cursor||!bodyText.substring(cursor,start).isBlank()&&start!=cursor)throw new IllegalArgumentException("Hueco u overlap en cuerpo");cursor=end;}
    if(!bodyText.substring(cursor).isBlank()&&cursor!=bodyText.length())throw new IllegalArgumentException("Cuerpo incompleto");
   }
   JsonObject status=new JsonObject();status.addProperty("documento_id",id);status.addProperty("fragmentos",n);status.addProperty("validacion",mode);result.add(status);
  }
  if(docs.isEmpty())throw new IllegalArgumentException("Corpus vacio");JsonObject report=new JsonObject();report.addProperty("estado","VALIDADO_PARA_PREPARAR_INDICE_NO_INDEXADO");report.addProperty("manifiesto_sha256",OcrPagina.hash(manifest));report.addProperty("total_fragmentos",count);report.add("documentos",result);return report;
 }
}
