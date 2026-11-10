package mx.edu.piloto;
import java.util.*;
import java.util.regex.Pattern;
/** Familias de claves admitidas. La clave referenciada no se convierte en identidad documental. */
public final class ClavesDocumentales {
 static final Pattern PATRON=Pattern.compile("(?<![\\p{L}\\p{N}/])(?:S\\s*\\.\\s*A\\s*\\.\\s*/?\\s*[0-9]+\\s*/\\s*[0-9]+|(?:TESO\\s*/\\s*SADM|SGEN\\s*/\\s*SADM|SADM\\s*/\\s*TESO|SADM|DGPE|DGCP|DGF|AGEN)\\s*/\\s*[0-9]+\\s*/\\s*[0-9]+)(?![\\p{L}\\p{N}/])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
 static Set<String> canonicas(String question){
  Set<String> keys=new LinkedHashSet<>();var matcher=PATRON.matcher(question);
  while(matcher.find()){String key=matcher.group().replaceAll("\\s+","").toUpperCase(Locale.ROOT);if(key.startsWith("S.A."))key=key.replaceFirst("^S\\.A\\./?","S.A./");keys.add(key);}return keys;
 }
}
