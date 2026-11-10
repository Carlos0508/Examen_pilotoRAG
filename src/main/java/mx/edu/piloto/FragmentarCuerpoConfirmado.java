package mx.edu.piloto;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Segmenta solo un cuerpo confirmado; metadatos confirmados se conservan separados de la transcripcion. */
public final class FragmentarCuerpoConfirmado {
 static final Gson JSON=new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("Uso: FragmentarCuerpoConfirmado <confirmacion.json> <salida-nueva>");
  Path approval=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();JsonObject a=JsonParser.parseString(Files.readString(approval,StandardCharsets.UTF_8)).getAsJsonObject();
  if(!a.get("estado").getAsString().equals("CUERPO_Y_DATOS_CONFIRMADOS_POR_USUARIO"))throw new IllegalArgumentException("Cuerpo sin confirmacion");
  Path source=approval.getParent().resolve(a.get("fuente_texto").getAsString()).normalize().toRealPath(),pdf=approval.getParent().resolve(a.get("fuente_pdf").getAsString()).normalize().toRealPath();
  String hash=OcrPagina.hash(source);
  if(!hash.equals(a.get("fuente_texto_sha256").getAsString())||!OcrPagina.hash(pdf).equals(a.get("fuente_pdf_sha256").getAsString()))throw new IllegalArgumentException("Fuentes alteradas");
  JsonObject metadata=a.getAsJsonObject("metadatos_confirmados");LocalDate.parse(metadata.get("fecha_emision").getAsString());for(String field:List.of("fecha_acceso_sistema","inicio_notificacion","fin_notificacion"))if(metadata.has(field)&&!metadata.get(field).isJsonNull())LocalDate.parse(metadata.get(field).getAsString());
  String text=Files.readString(source,StandardCharsets.UTF_8);var matcher=Pattern.compile("\\R[\\t ]*\\R(?:[\\t ]*\\R)*").matcher(text);List<int[]> spans=new ArrayList<>();int cursor=0;
  while(matcher.find()){if(matcher.start()>cursor)spans.add(new int[]{cursor,matcher.start()});cursor=matcher.end();}
  int tail=text.length();while(tail>cursor&&Character.isWhitespace(text.charAt(tail-1)))tail--;if(tail>cursor)spans.add(new int[]{cursor,tail});
  if(spans.isEmpty())throw new IllegalArgumentException("Cuerpo vacio");
  StringBuilder jsonl=new StringBuilder(),readable=new StringBuilder("# Circular "+metadata.get("clave").getAsString()+"\n\nFragmentos derivados del cuerpo confirmado, sin cambios de palabras.\n\n");
  int order=0;
  for(int[] span:spans){String body=text.substring(span[0],span[1]);if(body.isBlank()||body.length()>1800)throw new IllegalArgumentException("Parrafo requiere revision de subdivision; no se trunca");
   JsonObject c=new JsonObject();c.addProperty("id",a.get("documento_id").getAsString()+"-"+hash+"-par-%03d".formatted(++order));c.add("documento_id",a.get("documento_id"));c.add("expediente_id",a.get("expediente_id"));c.add("pagina",a.get("pagina"));
   for(var field:metadata.entrySet()){if(Set.of("id","documento_id","pagina","texto","orden","expediente_id","vigencia","remitente_cargo").contains(field.getKey()))throw new IllegalArgumentException("Metadato reservado: "+field.getKey());c.add(field.getKey(),field.getValue());}c.add("remitente_cargo",a.get("remitente_cargo"));c.add("vigencia",a.get("vigencia"));
   c.addProperty("estado_texto","CUERPO_CONFIRMADO_POR_USUARIO");c.addProperty("unidad","PARRAFO");c.addProperty("orden",order);c.addProperty("texto",body);c.addProperty("fuente_texto",source.toString());c.addProperty("fuente_texto_sha256",hash);c.addProperty("fuente_pdf",pdf.toString());c.add("fuente_pdf_sha256",a.get("fuente_pdf_sha256"));c.addProperty("inicio_utf16",span[0]);c.addProperty("fin_utf16_exclusivo",span[1]);c.addProperty("confirmacion_documental",approval.toString());c.addProperty("confirmacion_sha256",OcrPagina.hash(approval));c.addProperty("estrategia","parrafos-de-cuerpo-confirmado-v1");
   jsonl.append(JSON.toJson(c)).append('\n');readable.append("## Parrafo ").append(order).append("\n\n").append(body).append("\n\n");
  }
  if(Files.exists(out))throw new IllegalArgumentException("La salida ya existe");Files.createDirectories(out);Files.writeString(out.resolve("fragmentos.jsonl"),jsonl,StandardCharsets.UTF_8);Files.writeString(out.resolve("fragmentos-lectura.md"),readable,StandardCharsets.UTF_8);
  JsonObject trace=new JsonObject();trace.addProperty("fragmentos",order);trace.addProperty("estado","DERIVADOS_DE_CUERPO_CONFIRMADO_NO_INDEXADOS");trace.addProperty("fragmentos_sha256",OcrPagina.hash(out.resolve("fragmentos.jsonl")));trace.addProperty("confirmacion_sha256",OcrPagina.hash(approval));Files.writeString(out.resolve("ejecucion.json"),JSON.toJson(trace)+"\n",StandardCharsets.UTF_8);
  System.out.println("Fragmentos derivados: "+order+". No indexados.");
 }
}

