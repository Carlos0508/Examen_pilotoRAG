package mx.edu.piloto;
import com.google.gson.*;import java.util.*;
/** Agrupación temporal de una continuación declarada; no crea un fragmento persistente. */
public final class ContextoContinuidad{
 static List<JsonObject> units(List<JsonObject> chunks){
  Map<String,List<JsonObject>> groups=new LinkedHashMap<>();Set<String> consumed=new HashSet<>();
  for(var seed:chunks){if(!seed.has("relaciones_contexto"))continue;JsonObject first=null,second=null;
   for(var edge:seed.getAsJsonArray("relaciones_contexto")){var r=edge.getAsJsonObject();String type=r.get("tipo").getAsString();if(!type.startsWith("REQUISITO_GENERAL_")&&!type.startsWith("CONTINUACION_"))continue;
    for(var c:chunks)if(c.has("unidad_id")&&c.get("unidad_id").equals(r.get("origen"))&&c.get("documento_id").equals(seed.get("documento_id"))&&c.get("fuente_pdf_sha256").equals(seed.get("fuente_pdf_sha256"))){if(type.startsWith("REQUISITO_GENERAL_"))first=c;else second=c;}
   }
   if(first==null||second==null||first.get("pagina").getAsInt()+1!=second.get("pagina").getAsInt()||first.get("texto").getAsString().length()+second.get("texto").getAsString().length()>2800)continue;
   boolean same=true;for(String k:List.of("documento_id","fuente_pdf_sha256","clave","clave_padre","fecha_emision","fecha_periodo","tipo_documento","nivel_revision"))same&=Objects.equals(first.get(k),second.get(k));
   String a=first.get("id").getAsString(),b=second.get("id").getAsString();if(!same||consumed.contains(a)||consumed.contains(b))continue;groups.put(a,List.of(first,second));consumed.add(a);consumed.add(b);
  }
  List<JsonObject> result=new ArrayList<>();for(var c:chunks){String id=c.get("id").getAsString();if(groups.containsKey(id)){var u=ContextoListas.unit(groups.get(id));u.addProperty("id","grupo-continuacion-"+id);u.addProperty("unidad_id","Continuación temporal entre páginas");u.addProperty("seccion","CONTINUACION_PROPUESTA");u.addProperty("tipo_relacion_contexto","CONTINUACION_ORACION_PROPUESTA");result.add(u);}else if(!consumed.contains(id))result.add(c);}return result;
 }
}
