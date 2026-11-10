package mx.edu.piloto;
import com.google.gson.*;import java.util.*;
/** Guarda negativa para una fila explícita y un porcentaje; no autoriza respuestas. */
public final class ControlFilasTabla{
 static String rejection(String question,JsonObject decision,Map<String,JsonObject> allowed){
  String q=ControlEvidencia.normalized(question);if(!q.contains("porcentaje")||(!q.contains("fila")&&!q.contains("tabla")))return "";
  Set<String> terms=new HashSet<>(BusquedaHibrida.terms(BusquedaHibrida.lexicalQuestion(question)).keySet());terms.removeAll(Set.of("fila","tabla","porcentaje","porcentajes","multa","multas","anexo","circular","aparece","aparecen","indica","indican"));
  int best=0,second=0;String chosen=null;JsonObject target=null;
  for(var entry:allowed.entrySet()){
   var c=entry.getValue();if(!c.has("celdas_tabla"))continue;var cells=c.getAsJsonObject("celdas_tabla");if(!cells.has("FALTA COMETIDA")||cells.get("FALTA COMETIDA").isJsonNull()||!cells.has("PORCENTAJE DE MULTA"))continue;
   Set<String> overlap=new HashSet<>(BusquedaHibrida.terms(cells.get("FALTA COMETIDA").getAsString()).keySet());overlap.retainAll(terms);int n=overlap.size();
   if(n>best){second=best;best=n;chosen=entry.getKey();target=c;}else second=Math.max(second,n);
  }
  // Ante empate, pocos términos o varios supuestos plausibles, no se afirma correspondencia.
  if(target==null||best<3||second>=3||best-second<2)return "";
  if(target.getAsJsonObject("celdas_tabla").get("PORCENTAJE DE MULTA").isJsonNull())return "CELDA_PORCENTAJE_VACIA_EN_FILA_IDENTIFICADA";
  for(var id:decision.getAsJsonArray("fragmentos"))if(!id.getAsString().equals(chosen))return "PORCENTAJE_DE_OTRA_FILA_NO_RESPALDA_SUPUESTO";
  return "";
 }
}
