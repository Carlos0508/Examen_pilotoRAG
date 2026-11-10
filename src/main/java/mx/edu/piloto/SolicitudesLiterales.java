package mx.edu.piloto;
import java.util.*;
import java.util.regex.Pattern;

/** Descomposición conservadora de coordinaciones interrogativas explícitas, sin reescribir datos. */
public final class SolicitudesLiterales {
 private static final String HEAD="(?:(?:a\\s+partir\\s+de|a\\s+través\\s+de|a|de|en|con|por|para|desde|hasta|mediante|sobre|vía)\\s+)?(?:qué|cuál(?:es)?|quién(?:es)?|cuándo|dónde|cómo|cuánto(?:s|a|as)?)\\b";
 private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CHARACTER_CLASS;
 private static final Pattern SEPARATOR=Pattern.compile("(?:[,;]\\s*(?:y\\s+)?|\\by\\s+)(?=¿?"+HEAD+")",FLAGS);
 private static final Pattern FIRST=Pattern.compile(HEAD,FLAGS);
 private static final Pattern POLAR=Pattern.compile("(?:se|es|son|hay|puede(?:n)?|debe(?:n)?|deberá(?:n)?|está(?:n)?|incluye(?:n)?|permite(?:n)?|admite(?:n)?)\\b",FLAGS);
 static List<String> plan(String question){
  if(BusquedaMultidocumento.keys(question).size()>1)return null;
  // Varias preguntas o condiciones «¿Y si ...?» siguen la ruta previa, sin separar sus antecedentes.
  if(question.chars().filter(c->c=='¿').count()!=1||question.chars().filter(c->c=='?').count()!=1)return null;
  int open=question.indexOf('¿'),close=question.lastIndexOf('?');
  if(close<open||!question.substring(close+1).isBlank())return null;
  String body=question.substring(open+1,close);if(!FIRST.matcher(body).lookingAt()&&!POLAR.matcher(body).lookingAt())return null;
  String[] pieces=SEPARATOR.split(body,-1);if(pieces.length<2)return null;
  if(pieces.length>6)throw new IllegalArgumentException("Demasiadas solicitudes; dividir la pregunta");
  // No producir preguntas huérfanas como «en qué plazo» o «con qué autorización».
  // Ante elipsis muy breves se conserva íntegra la pregunta y su validación existente.
  for(String piece:pieces)if(piece.strip().split("\\s+").length<6)return null;
  List<String> literal=new ArrayList<>();String prefix=question.substring(0,open);
  for(String piece:pieces)literal.add(prefix+"¿"+piece.strip()+"?");
  var scoped=RespuestaPorPartes.preserveScope(question,literal);
  return RespuestaPorPartes.stabilizePlan(question,scoped);
 }
}
