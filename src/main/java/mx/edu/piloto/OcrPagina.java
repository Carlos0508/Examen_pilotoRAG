package mx.edu.piloto;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** OCR de una imagen y propuestas de parrafos trazables; requiere revision humana. */
public final class OcrPagina {
 record Word(String text, int left, int top, int width, int height, double confidence, String line) {}
 static String json(String s) {
  StringBuilder b=new StringBuilder("\"");
  for(char c:s.toCharArray()) switch(c) {
   case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
   case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
   default -> {if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c);}
  }
  return b.append('"').toString();
 }
 static String hash(Path p) throws Exception {
  var d=MessageDigest.getInstance("SHA-256");
  try(var in=Files.newInputStream(p)){byte[] buf=new byte[65536];for(int n;(n=in.read(buf))!=-1;)d.update(buf,0,n);}
  return HexFormat.of().formatHex(d.digest());
 }
 static void execute(List<String> cmd,Path log) throws Exception {
  Process p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
  if(!p.waitFor(Duration.ofMinutes(2).toMillis(),TimeUnit.MILLISECONDS)) {
   p.destroyForcibly(); p.waitFor(); throw new IllegalStateException("Tiempo agotado; revisar "+log);
  }
  if(p.exitValue()!=0) throw new IllegalStateException("Fallo del proceso; revisar "+log);
 }
 public static void main(String[] args) throws Exception {
  if(args.length!=5)throw new IllegalArgumentException("Uso: OcrPagina <tesseract.exe> <tessdata> <imagen> <pagina-original> <salida-nueva>");
  Path exe=Path.of(args[0]).toRealPath(), data=Path.of(args[1]).toRealPath(), image=Path.of(args[2]).toRealPath();
  int page=Integer.parseInt(args[3]);if(page<1)throw new IllegalArgumentException("Pagina debe ser positiva");
  Path out=Path.of(args[4]).toAbsolutePath().normalize();
  if(Files.exists(out))throw new IllegalArgumentException("La salida ya existe: "+out);
  Path trained=data.resolve("spa.traineddata");if(!Files.isRegularFile(trained))throw new IllegalArgumentException("Falta spa.traineddata");
  Files.createDirectories(out);
  execute(List.of(exe.toString(),"--version"),out.resolve("tesseract-version.txt"));
  execute(List.of(exe.toString(),image.toString(),out.resolve("ocr").toString(),"--tessdata-dir",data.toString(),"-l","spa","--psm","3","txt","tsv","hocr"),out.resolve("tesseract.log"));
  var groups=new LinkedHashMap<String,List<Word>>();
  var rows=Files.readAllLines(out.resolve("ocr.tsv"),StandardCharsets.UTF_8);
  if(rows.isEmpty())throw new IllegalStateException("TSV vacio");
  for(String row:rows.subList(1,rows.size())) {
   String[] c=row.split("\t",-1);if(c.length!=12)throw new IllegalArgumentException("TSV invalido");
   if(!c[0].equals("5")||c[11].isBlank())continue;
   String key=c[1]+":"+c[2]+":"+c[3];
   groups.computeIfAbsent(key,k->new ArrayList<>()).add(new Word(c[11],Integer.parseInt(c[6]),Integer.parseInt(c[7]),Integer.parseInt(c[8]),Integer.parseInt(c[9]),Double.parseDouble(c[10]),c[4]));
  }
  StringBuilder normalized=new StringBuilder(), records=new StringBuilder(), changes=new StringBuilder("propuesta,linea_anterior,linea_siguiente,operacion\n");
  int id=0;
  for(var group:groups.entrySet()) {
   List<Word> words=group.getValue();String key="p%03d-g%03d".formatted(page,++id);
   int left=Integer.MAX_VALUE,top=Integer.MAX_VALUE,right=0,bottom=0,low=0;double sum=0;
   StringBuilder text=new StringBuilder();Word previous=null;
   for(Word w:words){
    if(previous!=null){text.append(' ');if(!previous.line().equals(w.line()))changes.append(key).append(',').append(previous.line()).append(',').append(w.line()).append(",UNIR_CON_ESPACIO\n");}
    text.append(w.text());previous=w;
    left=Math.min(left,w.left());top=Math.min(top,w.top());right=Math.max(right,w.left()+w.width());bottom=Math.max(bottom,w.top()+w.height());
    sum+=w.confidence();if(w.confidence()<80)low++;
   }
   normalized.append(text).append("\n\n");
   records.append("{\"id\":").append(json(key)).append(",\"pagina\":").append(page)
    .append(",\"grupo_tsv\":").append(json(group.getKey())).append(",\"estado\":\"PENDIENTE_REVISION\",\"bbox_px\":[")
    .append(left).append(',').append(top).append(',').append(right-left).append(',').append(bottom-top)
    .append("],\"palabras\":").append(words.size()).append(",\"confianza_media_no_calibrada\":")
    .append(String.format(Locale.ROOT,"%.3f",sum/words.size())).append(",\"palabras_confianza_menor_80\":").append(low)
    .append(",\"texto\":").append(json(text.toString())).append("}\n");
  }
  Files.writeString(out.resolve("parrafos-propuestos.txt"),normalized,StandardCharsets.UTF_8);
  Files.writeString(out.resolve("parrafos-propuestos.jsonl"),records,StandardCharsets.UTF_8);
  Files.writeString(out.resolve("uniones.csv"),changes,StandardCharsets.UTF_8);
  Files.writeString(out.resolve("ejecucion.json"),"{\"pagina\":"+page+",\"imagen\":"+json(image.toString())+",\"imagen_sha256\":"+json(hash(image))+",\"tesseract_sha256\":"+json(hash(exe))+",\"spa_sha256\":"+json(hash(trained))+",\"idioma\":\"spa\",\"psm\":3,\"estado\":\"PENDIENTE_REVISION\"}\n",StandardCharsets.UTF_8);
  if(groups.isEmpty())throw new IllegalStateException("OCR sin palabras; resultados conservados para revision");
  System.out.println("Propuestas: "+groups.size()+". Sin correccion de palabras. Revisar: "+out);
 }
}

