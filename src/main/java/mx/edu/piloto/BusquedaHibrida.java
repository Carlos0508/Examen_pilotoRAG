package mx.edu.piloto;
import com.google.gson.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** BM25 sobre texto fuente y fusion por rangos; no modifica fragmentos ni citas. */
public final class BusquedaHibrida {
 static final double K1=1.2,B=0.75,RRF_K=60.0;
 static final Set<String> STOP=Set.of("a","al","algo","ante","bajo","cada","como","con","cual","cuales","cuando","de","del","desde","donde","el","ella","ellas","ellos","en","entre","es","esa","esas","ese","eses","eso","esos","esta","estas","este","estos","ha","hay","la","las","lo","los","o","para","por","que","quien","quienes","se","segun","ser","si","su","sus","un","una","unas","unos","y");
 static Map<String,Integer> terms(String text){
  String clean=Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
  var matcher=Pattern.compile("[\\p{L}\\p{N}]+(?:[-/][\\p{L}\\p{N}]+)*").matcher(clean);Map<String,Integer> result=new HashMap<>();
  while(matcher.find()){String word=matcher.group();if(!STOP.contains(word))result.merge(word,1,Integer::sum);}return result;
 }
 static String lexicalQuestion(String question){
  return ClavesDocumentales.PATRON.matcher(question).replaceAll(" ");
 }
 static JsonObject fuse(List<JsonObject> dense,String question){
  Map<String,Integer> query=terms(lexicalQuestion(question));int n=dense.size();List<Map<String,Integer>> documents=new ArrayList<>();Map<String,Integer> df=new HashMap<>();double average=0;
  for(var hit:dense){var t=terms(hit.getAsJsonObject("fragmento").get("texto").getAsString());documents.add(t);average+=t.values().stream().mapToInt(Integer::intValue).sum();for(String term:t.keySet())df.merge(term,1,Integer::sum);}
  average=n==0?0:average/n;
  double[] scores=new double[n];
  for(int i=0;i<n;i++){
   var t=documents.get(i);int length=t.values().stream().mapToInt(Integer::intValue).sum();
   for(String term:query.keySet()){
    int frequency=t.getOrDefault(term,0);if(frequency==0||average==0)continue;
    int frequencyDocs=df.getOrDefault(term,0);double idf=Math.log(1+(n-frequencyDocs+0.5)/(frequencyDocs+0.5));
    scores[i]+=idf*(frequency*(K1+1))/(frequency+K1*(1-B+B*length/average));
   }
  }
  List<Integer> lexical=new ArrayList<>();for(int i=0;i<n;i++)if(scores[i]>0)lexical.add(i);
  lexical.sort(Comparator.<Integer>comparingDouble(i->scores[i]).reversed().thenComparing(i->dense.get(i).getAsJsonObject("fragmento").get("id").getAsString()));
  int[] ranks=new int[n];for(int i=0;i<lexical.size();i++)ranks[lexical.get(i)]=i+1;
  List<JsonObject> fused=new ArrayList<>();
  for(int i=0;i<n;i++){
   JsonObject hit=dense.get(i).deepCopy();hit.addProperty("rango_denso",i+1);hit.addProperty("bm25",scores[i]);
   if(ranks[i]>0)hit.addProperty("rango_lexico",ranks[i]);else hit.add("rango_lexico",JsonNull.INSTANCE);
   hit.addProperty("puntuacion_fusion",1.0/(RRF_K+i+1)+(ranks[i]>0?1.0/(RRF_K+ranks[i]):0));fused.add(hit);
  }
  fused.sort(Comparator.comparingDouble((JsonObject h)->h.get("puntuacion_fusion").getAsDouble()).reversed().thenComparingInt(h->h.get("rango_denso").getAsInt()).thenComparing(h->h.getAsJsonObject("fragmento").get("id").getAsString()));
  JsonObject output=new JsonObject();output.addProperty("politica","coseno-bm25-rrf60-igual-v1");output.addProperty("bm25_k1",K1);output.addProperty("bm25_b",B);output.addProperty("rrf_k",RRF_K);output.addProperty("candidatos",n);output.addProperty("longitud_media_terminos",average);output.addProperty("documentos_con_coincidencia_lexica",lexical.size());
  JsonArray terms=new JsonArray();query.keySet().stream().sorted().forEach(terms::add);output.add("terminos_lexicos",terms);output.add("resultados",CorpusMultidocumento.JSON.toJsonTree(fused));return output;
 }
}
