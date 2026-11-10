package mx.edu.piloto;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Un parrafo revisado por fragmento; los limites del cuerpo se configuran por documento. */
public final class FragmentarCircular {
 static String required(Properties p,String key) {
  String value=p.getProperty(key);
  if(value==null||value.isBlank())throw new IllegalArgumentException("Falta propiedad: "+key);
  return value;
 }
 static String q(String s){return OcrPagina.json(s);}
 static int uniqueIndex(String text,String marker) {
  int start=text.indexOf(marker);
  if(start<0||text.indexOf(marker,start+marker.length())>=0)
   throw new IllegalArgumentException("El delimitador debe aparecer exactamente una vez: "+marker);
  return start;
 }
 public static void main(String[] args) throws Exception {
  if(args.length!=2)throw new IllegalArgumentException("Uso: FragmentarCircular <config.properties> <salida-nueva>");
  Path config=Path.of(args[0]).toRealPath(),out=Path.of(args[1]).toAbsolutePath().normalize();
  Properties p=new Properties();
  try(var r=Files.newBufferedReader(config,StandardCharsets.UTF_8)){p.load(r);}
  Path source=config.getParent().resolve(required(p,"fuente.texto")).normalize().toRealPath();
  Path pdf=config.getParent().resolve(required(p,"fuente.pdf")).normalize().toRealPath();
  String sourceHash=OcrPagina.hash(source),pdfHash=OcrPagina.hash(pdf);
  if(!sourceHash.equals(required(p,"fuente.sha256"))||!pdfHash.equals(required(p,"fuente.pdf.sha256")))
   throw new IllegalArgumentException("La fuente cambio; revisar antes de fragmentar.");
  String text=Files.readString(source,StandardCharsets.UTF_8);
  String document=required(p,"documento.id"),expediente=required(p,"expediente.id");
  String key=required(p,"clave"),date=required(p,"fecha.iso"),name=required(p,"remitente.nombre"),role=required(p,"remitente.cargo");
  LocalDate.parse(date);
  for(String evidence:List.of(key,required(p,"fecha.literal"),name,role))
   if(!text.contains(evidence))throw new IllegalArgumentException("Metadato sin evidencia literal: "+evidence);
  int page=Integer.parseInt(required(p,"pagina")),max=Integer.parseInt(required(p,"fragmento.max_caracteres"));
  if(page<1||max<1)throw new IllegalArgumentException("Pagina o limite invalido");
  int start=uniqueIndex(text,required(p,"cuerpo.inicio")),end=uniqueIndex(text,required(p,"cuerpo.fin"));
  if(end<=start)throw new IllegalArgumentException("Limites de cuerpo invertidos");
  // Exigir marcadores en limites de parrafo, no coincidencias dentro de una oracion.
  if(start>0&&!text.substring(0,start).matches("(?s).*\\R[\\t ]*\\R"))throw new IllegalArgumentException("Inicio no esta en limite de parrafo");
  if(end>0&&!text.substring(0,end).matches("(?s).*\\R[\\t ]*\\R"))throw new IllegalArgumentException("Fin no esta en limite de parrafo");
  String body=text.substring(start,end);
  var separator=Pattern.compile("\\R[\\t ]*\\R(?:[\\t ]*\\R)*").matcher(body);
  List<int[]> spans=new ArrayList<>();int cursor=0;
  while(separator.find()){if(separator.start()>cursor)spans.add(new int[]{start+cursor,start+separator.start()});cursor=separator.end();}
  if(cursor<body.length())spans.add(new int[]{start+cursor,end});
  if(spans.isEmpty())throw new IllegalArgumentException("Cuerpo vacio");
  StringBuilder jsonl=new StringBuilder();
  StringBuilder readable=new StringBuilder("# Fragmentos de la circular "+key+"\n\nEstrategia: un parrafo revisado por fragmento, sin solapamiento. Vigencia no determinada.\n\n");
  int index=0;
  for(int[] span:spans) {
   String content=text.substring(span[0],span[1]);
   if(content.isBlank()||content.length()>max)throw new IllegalArgumentException("Parrafo vacio o demasiado largo: requiere subdivision revisada, no truncamiento");
   String id=document+"-"+sourceHash+"-par-%03d".formatted(++index);
   jsonl.append("{\"id\":").append(q(id)).append(",\"documento_id\":").append(q(document))
    .append(",\"expediente_id\":").append(q(expediente)).append(",\"pagina\":").append(page)
    .append(",\"clave\":").append(q(key)).append(",\"fecha_emision\":").append(q(date))
    .append(",\"remitente_nombre\":").append(q(name)).append(",\"remitente_cargo\":").append(q(role))
    .append(",\"asunto_literal\":null,\"vigencia\":\"NO_DETERMINADA\",\"estado_texto\":\"REFERENCIA_PILOTO_CONFIRMADA_POR_USUARIO\"")
    .append(",\"unidad\":\"PARRAFO\",\"orden\":").append(index)
    .append(",\"texto\":").append(q(content)).append(",\"fuente_texto\":").append(q(source.toString()))
    .append(",\"fuente_texto_sha256\":").append(q(sourceHash)).append(",\"fuente_pdf\":").append(q(pdf.toString()))
    .append(",\"fuente_pdf_sha256\":").append(q(pdfHash)).append(",\"inicio_utf16\":").append(span[0])
    .append(",\"fin_utf16_exclusivo\":").append(span[1]).append(",\"estrategia\":\"parrafos-revisados-v1\"}\n");
   readable.append("## Parrafo ").append(index).append(" · pagina ").append(page).append("\n\n").append(content).append("\n\n");
  }
  // Escribir solo despues de validar todas las unidades; nunca sobrescribir una ejecucion.
  if(Files.exists(out))throw new IllegalArgumentException("La salida ya existe: "+out);
  Files.createDirectories(out);
  Files.writeString(out.resolve("fragmentos.jsonl"),jsonl,StandardCharsets.UTF_8);
  Files.writeString(out.resolve("fragmentos-lectura.md"),readable,StandardCharsets.UTF_8);
  Files.writeString(out.resolve("ejecucion.json"),"{\"fragmentos\":"+spans.size()+",\"inicio_cuerpo_utf16\":"+start+",\"fin_cuerpo_utf16_exclusivo\":"+end+",\"config_sha256\":"+q(OcrPagina.hash(config))+",\"limite_caracteres_utf16\":"+max+",\"solapamiento\":0,\"estrategia\":\"parrafos-revisados-v1\",\"tokens\":null,\"indexado\":false}\n",StandardCharsets.UTF_8);
  System.out.println("Fragmentos generados: "+spans.size()+". Sin indexar. "+out);
 }
}
