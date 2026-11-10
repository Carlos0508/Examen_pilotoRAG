package mx.edu.piloto;
import com.google.gson.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Controles conservadores de requisitos expresos; no son un verificador semantico general. */
public final class ControlEvidencia {
 static String normalized(String s){return Normalizer.normalize(s,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);}
 static String rejection(String question,JsonObject decision,Map<String,JsonObject> allowed){
  if(!decision.get("estado").getAsString().equals("EVIDENCIA_ENCONTRADA"))return "";
  String tableRejection=ControlFilasTabla.rejection(question,decision,allowed);if(!tableRejection.isEmpty())return tableRejection;
  String q=normalized(question);StringBuilder evidence=new StringBuilder();
  for(var id:decision.getAsJsonArray("fragmentos"))evidence.append(allowed.get(id.getAsString()).get("texto").getAsString()).append('\n');
  String text=normalized(evidence.toString());
  String listRejection=ContextoListas.rejection(question,evidence.toString(),allowed);if(!listRejection.isEmpty())return listRejection;
  var requestedStep=Pattern.compile("\\bpaso\\s+([0-9]+)\\b").matcher(q);
  while(requestedStep.find()){int step=Integer.parseInt(requestedStep.group(1));for(var c:allowed.values())if(c.has("pasos_excluidos"))for(var value:c.getAsJsonArray("pasos_excluidos"))if(value.getAsInt()==step)return "PASO_PENDIENTE_EXCLUIDO";}

  if(q.matches("(?s).*\\bbanco\\b.*")&&!Pattern.compile("\\bbanco\\b").matcher(text).find())return "BANCO_NO_EXPLICITO_EN_CITAS";
  var namedBank=Pattern.compile("\\bBanco\\s+(?:(?:del|de|la|el)\\s+)?[\\p{Lu}][\\p{L}]+(?:\\s+[\\p{Lu}][\\p{L}]+){0,2}").matcher(question);
  while(namedBank.find())if(!text.contains(normalized(namedBank.group())))return "BANCO_SOLICITADO_NO_EXPLICITO";
  // Las cifras de claves documentales no son calificadores temporales.
  String temporalQuestion=ClavesDocumentales.PATRON.matcher(q).replaceAll("");
  var requestedYears=Pattern.compile("\\b(?:18|19|20)[0-9]{2}\\b").matcher(temporalQuestion);
  StringBuilder datedEvidence=new StringBuilder(text);
  for(var id:decision.getAsJsonArray("fragmentos")){var c=allowed.get(id.getAsString());for(String field:List.of("fecha_emision","fecha_periodo"))if(c.has(field)&&!c.get(field).isJsonNull())datedEvidence.append(' ').append(c.get(field).getAsString());}
  while(requestedYears.find())if(!Pattern.compile("(?<![0-9])"+requestedYears.group()+"(?![0-9])").matcher(datedEvidence).find())return "ANIO_SOLICITADO_NO_EXPLICITO_EN_CITAS";
  String number="(?:[0-9]+(?:[.,][0-9]+)?|un|uno|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|dieciseis|diecisiete|dieciocho|diecinueve|veinte|treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|noventa|cien|ciento|mil)";
  boolean duration=q.matches("(?s).*(?:plazo|duracion|cuantos|cuantas).*\\bdias?\\b.*");
  if(duration&&!Pattern.compile("\\b"+number+"\\s+dias?\\b").matcher(text).find())return "DURACION_EN_DIAS_NO_EXPLICITA";
  if((q.contains("porcentaje")||q.contains("por ciento"))&&!Pattern.compile("\\b"+number+"\\s*(?:%|por ciento)" ).matcher(text).find())return "PORCENTAJE_NO_EXPLICITO";
  // Necesario, no suficiente: fechas, cuentas y números de partidas no son importes.
  if(q.contains("monto")||q.contains("importe")){
   String quantity="(?:[0-9]+(?:[.,][0-9]+)*|un|una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|dieciseis|veinte|treinta|cuarenta|cincuenta|sesenta|setenta|ochenta|noventa|cien|ciento|doscientos|quinientos|mil|millon|millones)";
   String amount="(?:[$€]\\s*[0-9]+(?:[.,][0-9]+)*|\\b(?:mxn|usd|eur)\\s+[0-9]+(?:[.,][0-9]+)*|\\b"+quantity+"(?:\\s+"+quantity+")*\\s*\\(?\\s*(?:pesos|dolares|euros|mxn|usd|eur|%|por ciento|salarios?\\s+(?:minimos?|tabulares?)|umas?))";
   if(!Pattern.compile(amount).matcher(text).find())return "IMPORTE_O_REGLA_CUANTITATIVA_NO_EXPLICITO";
  }
  if((q.contains("vigente")||q.contains("vigencia"))&&(q.contains("actual")||q.contains("hoy")||q.contains("sigue"))&&allowed.values().stream().noneMatch(c->!c.get("vigencia").getAsString().equals("NO_DETERMINADA")))return "VIGENCIA_ACTUAL_NO_ACREDITADA";
  return "";
 }
}

