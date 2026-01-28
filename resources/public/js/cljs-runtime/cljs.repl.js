goog.provide('cljs.repl');
cljs.repl.print_doc = (function cljs$repl$print_doc(p__18650){
var map__18651 = p__18650;
var map__18651__$1 = cljs.core.__destructure_map(map__18651);
var m = map__18651__$1;
var n = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18651__$1,new cljs.core.Keyword(null,"ns","ns",441598760));
var nm = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18651__$1,new cljs.core.Keyword(null,"name","name",1843675177));
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["-------------------------"], 0));

cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (){var or__5142__auto__ = new cljs.core.Keyword(null,"spec","spec",347520401).cljs$core$IFn$_invoke$arity$1(m);
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var temp__5823__auto__ = new cljs.core.Keyword(null,"ns","ns",441598760).cljs$core$IFn$_invoke$arity$1(m);
if(cljs.core.truth_(temp__5823__auto__)){
var ns = temp__5823__auto__;
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(ns)+"/");
} else {
return null;
}
})())+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"name","name",1843675177).cljs$core$IFn$_invoke$arity$1(m)));
}
})()], 0));

if(cljs.core.truth_(new cljs.core.Keyword(null,"protocol","protocol",652470118).cljs$core$IFn$_invoke$arity$1(m))){
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Protocol"], 0));
} else {
}

if(cljs.core.truth_(new cljs.core.Keyword(null,"forms","forms",2045992350).cljs$core$IFn$_invoke$arity$1(m))){
var seq__18661_19148 = cljs.core.seq(new cljs.core.Keyword(null,"forms","forms",2045992350).cljs$core$IFn$_invoke$arity$1(m));
var chunk__18662_19149 = null;
var count__18663_19150 = (0);
var i__18664_19151 = (0);
while(true){
if((i__18664_19151 < count__18663_19150)){
var f_19156 = chunk__18662_19149.cljs$core$IIndexed$_nth$arity$2(null,i__18664_19151);
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["  ",f_19156], 0));


var G__19158 = seq__18661_19148;
var G__19159 = chunk__18662_19149;
var G__19160 = count__18663_19150;
var G__19161 = (i__18664_19151 + (1));
seq__18661_19148 = G__19158;
chunk__18662_19149 = G__19159;
count__18663_19150 = G__19160;
i__18664_19151 = G__19161;
continue;
} else {
var temp__5823__auto___19162 = cljs.core.seq(seq__18661_19148);
if(temp__5823__auto___19162){
var seq__18661_19164__$1 = temp__5823__auto___19162;
if(cljs.core.chunked_seq_QMARK_(seq__18661_19164__$1)){
var c__5673__auto___19165 = cljs.core.chunk_first(seq__18661_19164__$1);
var G__19166 = cljs.core.chunk_rest(seq__18661_19164__$1);
var G__19167 = c__5673__auto___19165;
var G__19168 = cljs.core.count(c__5673__auto___19165);
var G__19169 = (0);
seq__18661_19148 = G__19166;
chunk__18662_19149 = G__19167;
count__18663_19150 = G__19168;
i__18664_19151 = G__19169;
continue;
} else {
var f_19170 = cljs.core.first(seq__18661_19164__$1);
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["  ",f_19170], 0));


var G__19171 = cljs.core.next(seq__18661_19164__$1);
var G__19172 = null;
var G__19173 = (0);
var G__19174 = (0);
seq__18661_19148 = G__19171;
chunk__18662_19149 = G__19172;
count__18663_19150 = G__19173;
i__18664_19151 = G__19174;
continue;
}
} else {
}
}
break;
}
} else {
if(cljs.core.truth_(new cljs.core.Keyword(null,"arglists","arglists",1661989754).cljs$core$IFn$_invoke$arity$1(m))){
var arglists_19177 = new cljs.core.Keyword(null,"arglists","arglists",1661989754).cljs$core$IFn$_invoke$arity$1(m);
if(cljs.core.truth_((function (){var or__5142__auto__ = new cljs.core.Keyword(null,"macro","macro",-867863404).cljs$core$IFn$_invoke$arity$1(m);
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return new cljs.core.Keyword(null,"repl-special-function","repl-special-function",1262603725).cljs$core$IFn$_invoke$arity$1(m);
}
})())){
cljs.core.prn.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([arglists_19177], 0));
} else {
cljs.core.prn.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Symbol(null,"quote","quote",1377916282,null),cljs.core.first(arglists_19177)))?cljs.core.second(arglists_19177):arglists_19177)], 0));
}
} else {
}
}

if(cljs.core.truth_(new cljs.core.Keyword(null,"special-form","special-form",-1326536374).cljs$core$IFn$_invoke$arity$1(m))){
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Special Form"], 0));

cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([" ",new cljs.core.Keyword(null,"doc","doc",1913296891).cljs$core$IFn$_invoke$arity$1(m)], 0));

if(cljs.core.contains_QMARK_(m,new cljs.core.Keyword(null,"url","url",276297046))){
if(cljs.core.truth_(new cljs.core.Keyword(null,"url","url",276297046).cljs$core$IFn$_invoke$arity$1(m))){
return cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(""+"\n  Please see http://clojure.org/"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"url","url",276297046).cljs$core$IFn$_invoke$arity$1(m)))], 0));
} else {
return null;
}
} else {
return cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(""+"\n  Please see http://clojure.org/special_forms#"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"name","name",1843675177).cljs$core$IFn$_invoke$arity$1(m)))], 0));
}
} else {
if(cljs.core.truth_(new cljs.core.Keyword(null,"macro","macro",-867863404).cljs$core$IFn$_invoke$arity$1(m))){
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Macro"], 0));
} else {
}

if(cljs.core.truth_(new cljs.core.Keyword(null,"spec","spec",347520401).cljs$core$IFn$_invoke$arity$1(m))){
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Spec"], 0));
} else {
}

if(cljs.core.truth_(new cljs.core.Keyword(null,"repl-special-function","repl-special-function",1262603725).cljs$core$IFn$_invoke$arity$1(m))){
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["REPL Special Function"], 0));
} else {
}

cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([" ",new cljs.core.Keyword(null,"doc","doc",1913296891).cljs$core$IFn$_invoke$arity$1(m)], 0));

if(cljs.core.truth_(new cljs.core.Keyword(null,"protocol","protocol",652470118).cljs$core$IFn$_invoke$arity$1(m))){
var seq__18686_19185 = cljs.core.seq(new cljs.core.Keyword(null,"methods","methods",453930866).cljs$core$IFn$_invoke$arity$1(m));
var chunk__18687_19186 = null;
var count__18688_19187 = (0);
var i__18689_19188 = (0);
while(true){
if((i__18689_19188 < count__18688_19187)){
var vec__18714_19190 = chunk__18687_19186.cljs$core$IIndexed$_nth$arity$2(null,i__18689_19188);
var name_19191 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18714_19190,(0),null);
var map__18717_19192 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18714_19190,(1),null);
var map__18717_19193__$1 = cljs.core.__destructure_map(map__18717_19192);
var doc_19194 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18717_19193__$1,new cljs.core.Keyword(null,"doc","doc",1913296891));
var arglists_19195 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18717_19193__$1,new cljs.core.Keyword(null,"arglists","arglists",1661989754));
cljs.core.println();

cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([" ",name_19191], 0));

cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([" ",arglists_19195], 0));

if(cljs.core.truth_(doc_19194)){
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([" ",doc_19194], 0));
} else {
}


var G__19201 = seq__18686_19185;
var G__19202 = chunk__18687_19186;
var G__19203 = count__18688_19187;
var G__19204 = (i__18689_19188 + (1));
seq__18686_19185 = G__19201;
chunk__18687_19186 = G__19202;
count__18688_19187 = G__19203;
i__18689_19188 = G__19204;
continue;
} else {
var temp__5823__auto___19207 = cljs.core.seq(seq__18686_19185);
if(temp__5823__auto___19207){
var seq__18686_19208__$1 = temp__5823__auto___19207;
if(cljs.core.chunked_seq_QMARK_(seq__18686_19208__$1)){
var c__5673__auto___19210 = cljs.core.chunk_first(seq__18686_19208__$1);
var G__19213 = cljs.core.chunk_rest(seq__18686_19208__$1);
var G__19214 = c__5673__auto___19210;
var G__19215 = cljs.core.count(c__5673__auto___19210);
var G__19216 = (0);
seq__18686_19185 = G__19213;
chunk__18687_19186 = G__19214;
count__18688_19187 = G__19215;
i__18689_19188 = G__19216;
continue;
} else {
var vec__18728_19217 = cljs.core.first(seq__18686_19208__$1);
var name_19218 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18728_19217,(0),null);
var map__18731_19219 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18728_19217,(1),null);
var map__18731_19220__$1 = cljs.core.__destructure_map(map__18731_19219);
var doc_19221 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18731_19220__$1,new cljs.core.Keyword(null,"doc","doc",1913296891));
var arglists_19222 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18731_19220__$1,new cljs.core.Keyword(null,"arglists","arglists",1661989754));
cljs.core.println();

cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([" ",name_19218], 0));

cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([" ",arglists_19222], 0));

if(cljs.core.truth_(doc_19221)){
cljs.core.println.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([" ",doc_19221], 0));
} else {
}


var G__19223 = cljs.core.next(seq__18686_19208__$1);
var G__19224 = null;
var G__19225 = (0);
var G__19226 = (0);
seq__18686_19185 = G__19223;
chunk__18687_19186 = G__19224;
count__18688_19187 = G__19225;
i__18689_19188 = G__19226;
continue;
}
} else {
}
}
break;
}
} else {
}

if(cljs.core.truth_(n)){
var temp__5823__auto__ = cljs.spec.alpha.get_spec(cljs.core.symbol.cljs$core$IFn$_invoke$arity$2((""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.ns_name(n))),cljs.core.name(nm)));
if(cljs.core.truth_(temp__5823__auto__)){
var fnspec = temp__5823__auto__;
cljs.core.print.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Spec"], 0));

var seq__18744 = cljs.core.seq(new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"args","args",1315556576),new cljs.core.Keyword(null,"ret","ret",-468222814),new cljs.core.Keyword(null,"fn","fn",-1175266204)], null));
var chunk__18745 = null;
var count__18746 = (0);
var i__18747 = (0);
while(true){
if((i__18747 < count__18746)){
var role = chunk__18745.cljs$core$IIndexed$_nth$arity$2(null,i__18747);
var temp__5823__auto___19232__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(fnspec,role);
if(cljs.core.truth_(temp__5823__auto___19232__$1)){
var spec_19233 = temp__5823__auto___19232__$1;
cljs.core.print.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(""+"\n "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.name(role))+":"),cljs.spec.alpha.describe(spec_19233)], 0));
} else {
}


var G__19234 = seq__18744;
var G__19235 = chunk__18745;
var G__19236 = count__18746;
var G__19237 = (i__18747 + (1));
seq__18744 = G__19234;
chunk__18745 = G__19235;
count__18746 = G__19236;
i__18747 = G__19237;
continue;
} else {
var temp__5823__auto____$1 = cljs.core.seq(seq__18744);
if(temp__5823__auto____$1){
var seq__18744__$1 = temp__5823__auto____$1;
if(cljs.core.chunked_seq_QMARK_(seq__18744__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__18744__$1);
var G__19238 = cljs.core.chunk_rest(seq__18744__$1);
var G__19239 = c__5673__auto__;
var G__19240 = cljs.core.count(c__5673__auto__);
var G__19241 = (0);
seq__18744 = G__19238;
chunk__18745 = G__19239;
count__18746 = G__19240;
i__18747 = G__19241;
continue;
} else {
var role = cljs.core.first(seq__18744__$1);
var temp__5823__auto___19242__$2 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(fnspec,role);
if(cljs.core.truth_(temp__5823__auto___19242__$2)){
var spec_19243 = temp__5823__auto___19242__$2;
cljs.core.print.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(""+"\n "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.name(role))+":"),cljs.spec.alpha.describe(spec_19243)], 0));
} else {
}


var G__19244 = cljs.core.next(seq__18744__$1);
var G__19245 = null;
var G__19246 = (0);
var G__19247 = (0);
seq__18744 = G__19244;
chunk__18745 = G__19245;
count__18746 = G__19246;
i__18747 = G__19247;
continue;
}
} else {
return null;
}
}
break;
}
} else {
return null;
}
} else {
return null;
}
}
});
/**
 * Constructs a data representation for a Error with keys:
 *  :cause - root cause message
 *  :phase - error phase
 *  :via - cause chain, with cause keys:
 *           :type - exception class symbol
 *           :message - exception message
 *           :data - ex-data
 *           :at - top stack element
 *  :trace - root cause stack elements
 */
cljs.repl.Error__GT_map = (function cljs$repl$Error__GT_map(o){
return cljs.core.Throwable__GT_map(o);
});
/**
 * Returns an analysis of the phase, error, cause, and location of an error that occurred
 *   based on Throwable data, as returned by Throwable->map. All attributes other than phase
 *   are optional:
 *  :clojure.error/phase - keyword phase indicator, one of:
 *    :read-source :compile-syntax-check :compilation :macro-syntax-check :macroexpansion
 *    :execution :read-eval-result :print-eval-result
 *  :clojure.error/source - file name (no path)
 *  :clojure.error/line - integer line number
 *  :clojure.error/column - integer column number
 *  :clojure.error/symbol - symbol being expanded/compiled/invoked
 *  :clojure.error/class - cause exception class symbol
 *  :clojure.error/cause - cause exception message
 *  :clojure.error/spec - explain-data for spec error
 */
cljs.repl.ex_triage = (function cljs$repl$ex_triage(datafied_throwable){
var map__18806 = datafied_throwable;
var map__18806__$1 = cljs.core.__destructure_map(map__18806);
var via = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18806__$1,new cljs.core.Keyword(null,"via","via",-1904457336));
var trace = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18806__$1,new cljs.core.Keyword(null,"trace","trace",-1082747415));
var phase = cljs.core.get.cljs$core$IFn$_invoke$arity$3(map__18806__$1,new cljs.core.Keyword(null,"phase","phase",575722892),new cljs.core.Keyword(null,"execution","execution",253283524));
var map__18808 = cljs.core.last(via);
var map__18808__$1 = cljs.core.__destructure_map(map__18808);
var type = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18808__$1,new cljs.core.Keyword(null,"type","type",1174270348));
var message = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18808__$1,new cljs.core.Keyword(null,"message","message",-406056002));
var data = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18808__$1,new cljs.core.Keyword(null,"data","data",-232669377));
var map__18809 = data;
var map__18809__$1 = cljs.core.__destructure_map(map__18809);
var problems = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18809__$1,new cljs.core.Keyword("cljs.spec.alpha","problems","cljs.spec.alpha/problems",447400814));
var fn = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18809__$1,new cljs.core.Keyword("cljs.spec.alpha","fn","cljs.spec.alpha/fn",408600443));
var caller = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18809__$1,new cljs.core.Keyword("cljs.spec.test.alpha","caller","cljs.spec.test.alpha/caller",-398302390));
var map__18810 = new cljs.core.Keyword(null,"data","data",-232669377).cljs$core$IFn$_invoke$arity$1(cljs.core.first(via));
var map__18810__$1 = cljs.core.__destructure_map(map__18810);
var top_data = map__18810__$1;
var source = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18810__$1,new cljs.core.Keyword("clojure.error","source","clojure.error/source",-2011936397));
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3((function (){var G__18835 = phase;
var G__18835__$1 = (((G__18835 instanceof cljs.core.Keyword))?G__18835.fqn:null);
switch (G__18835__$1) {
case "read-source":
var map__18842 = data;
var map__18842__$1 = cljs.core.__destructure_map(map__18842);
var line = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18842__$1,new cljs.core.Keyword("clojure.error","line","clojure.error/line",-1816287471));
var column = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__18842__$1,new cljs.core.Keyword("clojure.error","column","clojure.error/column",304721553));
var G__18851 = cljs.core.merge.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"data","data",-232669377).cljs$core$IFn$_invoke$arity$1(cljs.core.second(via)),top_data], 0));
var G__18851__$1 = (cljs.core.truth_(source)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18851,new cljs.core.Keyword("clojure.error","source","clojure.error/source",-2011936397),source):G__18851);
var G__18851__$2 = (cljs.core.truth_((function (){var fexpr__18855 = new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, ["NO_SOURCE_PATH",null,"NO_SOURCE_FILE",null], null), null);
return (fexpr__18855.cljs$core$IFn$_invoke$arity$1 ? fexpr__18855.cljs$core$IFn$_invoke$arity$1(source) : fexpr__18855.call(null,source));
})())?cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(G__18851__$1,new cljs.core.Keyword("clojure.error","source","clojure.error/source",-2011936397)):G__18851__$1);
if(cljs.core.truth_(message)){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18851__$2,new cljs.core.Keyword("clojure.error","cause","clojure.error/cause",-1879175742),message);
} else {
return G__18851__$2;
}

break;
case "compile-syntax-check":
case "compilation":
case "macro-syntax-check":
case "macroexpansion":
var G__18862 = top_data;
var G__18862__$1 = (cljs.core.truth_(source)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18862,new cljs.core.Keyword("clojure.error","source","clojure.error/source",-2011936397),source):G__18862);
var G__18862__$2 = (cljs.core.truth_((function (){var fexpr__18872 = new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, ["NO_SOURCE_PATH",null,"NO_SOURCE_FILE",null], null), null);
return (fexpr__18872.cljs$core$IFn$_invoke$arity$1 ? fexpr__18872.cljs$core$IFn$_invoke$arity$1(source) : fexpr__18872.call(null,source));
})())?cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(G__18862__$1,new cljs.core.Keyword("clojure.error","source","clojure.error/source",-2011936397)):G__18862__$1);
var G__18862__$3 = (cljs.core.truth_(type)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18862__$2,new cljs.core.Keyword("clojure.error","class","clojure.error/class",278435890),type):G__18862__$2);
var G__18862__$4 = (cljs.core.truth_(message)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18862__$3,new cljs.core.Keyword("clojure.error","cause","clojure.error/cause",-1879175742),message):G__18862__$3);
if(cljs.core.truth_(problems)){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18862__$4,new cljs.core.Keyword("clojure.error","spec","clojure.error/spec",2055032595),data);
} else {
return G__18862__$4;
}

break;
case "read-eval-result":
case "print-eval-result":
var vec__18883 = cljs.core.first(trace);
var source__$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18883,(0),null);
var method = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18883,(1),null);
var file = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18883,(2),null);
var line = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18883,(3),null);
var G__18904 = top_data;
var G__18904__$1 = (cljs.core.truth_(line)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18904,new cljs.core.Keyword("clojure.error","line","clojure.error/line",-1816287471),line):G__18904);
var G__18904__$2 = (cljs.core.truth_(file)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18904__$1,new cljs.core.Keyword("clojure.error","source","clojure.error/source",-2011936397),file):G__18904__$1);
var G__18904__$3 = (cljs.core.truth_((function (){var and__5140__auto__ = source__$1;
if(cljs.core.truth_(and__5140__auto__)){
return method;
} else {
return and__5140__auto__;
}
})())?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18904__$2,new cljs.core.Keyword("clojure.error","symbol","clojure.error/symbol",1544821994),(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[source__$1,method],null))):G__18904__$2);
var G__18904__$4 = (cljs.core.truth_(type)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18904__$3,new cljs.core.Keyword("clojure.error","class","clojure.error/class",278435890),type):G__18904__$3);
if(cljs.core.truth_(message)){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18904__$4,new cljs.core.Keyword("clojure.error","cause","clojure.error/cause",-1879175742),message);
} else {
return G__18904__$4;
}

break;
case "execution":
var vec__18937 = cljs.core.first(trace);
var source__$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18937,(0),null);
var method = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18937,(1),null);
var file = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18937,(2),null);
var line = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__18937,(3),null);
var file__$1 = cljs.core.first(cljs.core.remove.cljs$core$IFn$_invoke$arity$2((function (p1__18800_SHARP_){
var or__5142__auto__ = (p1__18800_SHARP_ == null);
if(or__5142__auto__){
return or__5142__auto__;
} else {
var fexpr__18957 = new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, ["NO_SOURCE_PATH",null,"NO_SOURCE_FILE",null], null), null);
return (fexpr__18957.cljs$core$IFn$_invoke$arity$1 ? fexpr__18957.cljs$core$IFn$_invoke$arity$1(p1__18800_SHARP_) : fexpr__18957.call(null,p1__18800_SHARP_));
}
}),new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"file","file",-1269645878).cljs$core$IFn$_invoke$arity$1(caller),file], null)));
var err_line = (function (){var or__5142__auto__ = new cljs.core.Keyword(null,"line","line",212345235).cljs$core$IFn$_invoke$arity$1(caller);
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return line;
}
})();
var G__18966 = new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword("clojure.error","class","clojure.error/class",278435890),type], null);
var G__18966__$1 = (cljs.core.truth_(err_line)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18966,new cljs.core.Keyword("clojure.error","line","clojure.error/line",-1816287471),err_line):G__18966);
var G__18966__$2 = (cljs.core.truth_(message)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18966__$1,new cljs.core.Keyword("clojure.error","cause","clojure.error/cause",-1879175742),message):G__18966__$1);
var G__18966__$3 = (cljs.core.truth_((function (){var or__5142__auto__ = fn;
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
var and__5140__auto__ = source__$1;
if(cljs.core.truth_(and__5140__auto__)){
return method;
} else {
return and__5140__auto__;
}
}
})())?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18966__$2,new cljs.core.Keyword("clojure.error","symbol","clojure.error/symbol",1544821994),(function (){var or__5142__auto__ = fn;
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return (new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[source__$1,method],null));
}
})()):G__18966__$2);
var G__18966__$4 = (cljs.core.truth_(file__$1)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18966__$3,new cljs.core.Keyword("clojure.error","source","clojure.error/source",-2011936397),file__$1):G__18966__$3);
if(cljs.core.truth_(problems)){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__18966__$4,new cljs.core.Keyword("clojure.error","spec","clojure.error/spec",2055032595),data);
} else {
return G__18966__$4;
}

break;
default:
throw (new Error((""+"No matching clause: "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(G__18835__$1))));

}
})(),new cljs.core.Keyword("clojure.error","phase","clojure.error/phase",275140358),phase);
});
/**
 * Returns a string from exception data, as produced by ex-triage.
 *   The first line summarizes the exception phase and location.
 *   The subsequent lines describe the cause.
 */
cljs.repl.ex_str = (function cljs$repl$ex_str(p__19007){
var map__19008 = p__19007;
var map__19008__$1 = cljs.core.__destructure_map(map__19008);
var triage_data = map__19008__$1;
var phase = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__19008__$1,new cljs.core.Keyword("clojure.error","phase","clojure.error/phase",275140358));
var source = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__19008__$1,new cljs.core.Keyword("clojure.error","source","clojure.error/source",-2011936397));
var line = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__19008__$1,new cljs.core.Keyword("clojure.error","line","clojure.error/line",-1816287471));
var column = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__19008__$1,new cljs.core.Keyword("clojure.error","column","clojure.error/column",304721553));
var symbol = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__19008__$1,new cljs.core.Keyword("clojure.error","symbol","clojure.error/symbol",1544821994));
var class$ = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__19008__$1,new cljs.core.Keyword("clojure.error","class","clojure.error/class",278435890));
var cause = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__19008__$1,new cljs.core.Keyword("clojure.error","cause","clojure.error/cause",-1879175742));
var spec = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__19008__$1,new cljs.core.Keyword("clojure.error","spec","clojure.error/spec",2055032595));
var loc = (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5142__auto__ = source;
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return "<cljs repl>";
}
})())+":"+cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var or__5142__auto__ = line;
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return (1);
}
})())+cljs.core.str.cljs$core$IFn$_invoke$arity$1((cljs.core.truth_(column)?(""+":"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(column)):"")));
var class_name = cljs.core.name((function (){var or__5142__auto__ = class$;
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return "";
}
})());
var simple_class = class_name;
var cause_type = ((cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, ["RuntimeException",null,"Exception",null], null), null),simple_class))?"":(""+" ("+cljs.core.str.cljs$core$IFn$_invoke$arity$1(simple_class)+")"));
var format = goog.string.format;
var G__19021 = phase;
var G__19021__$1 = (((G__19021 instanceof cljs.core.Keyword))?G__19021.fqn:null);
switch (G__19021__$1) {
case "read-source":
return (format.cljs$core$IFn$_invoke$arity$3 ? format.cljs$core$IFn$_invoke$arity$3("Syntax error reading source at (%s).\n%s\n",loc,cause) : format.call(null,"Syntax error reading source at (%s).\n%s\n",loc,cause));

break;
case "macro-syntax-check":
var G__19023 = "Syntax error macroexpanding %sat (%s).\n%s";
var G__19024 = (cljs.core.truth_(symbol)?(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(symbol)+" "):"");
var G__19025 = loc;
var G__19026 = (cljs.core.truth_(spec)?(function (){var sb__5795__auto__ = (new goog.string.StringBuffer());
var _STAR_print_newline_STAR__orig_val__19030_19329 = cljs.core._STAR_print_newline_STAR_;
var _STAR_print_fn_STAR__orig_val__19031_19330 = cljs.core._STAR_print_fn_STAR_;
var _STAR_print_newline_STAR__temp_val__19032_19331 = true;
var _STAR_print_fn_STAR__temp_val__19033_19332 = (function (x__5796__auto__){
return sb__5795__auto__.append(x__5796__auto__);
});
(cljs.core._STAR_print_newline_STAR_ = _STAR_print_newline_STAR__temp_val__19032_19331);

(cljs.core._STAR_print_fn_STAR_ = _STAR_print_fn_STAR__temp_val__19033_19332);

try{cljs.spec.alpha.explain_out(cljs.core.update.cljs$core$IFn$_invoke$arity$3(spec,new cljs.core.Keyword("cljs.spec.alpha","problems","cljs.spec.alpha/problems",447400814),(function (probs){
return cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p1__19000_SHARP_){
return cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(p1__19000_SHARP_,new cljs.core.Keyword(null,"in","in",-1531184865));
}),probs);
}))
);
}finally {(cljs.core._STAR_print_fn_STAR_ = _STAR_print_fn_STAR__orig_val__19031_19330);

(cljs.core._STAR_print_newline_STAR_ = _STAR_print_newline_STAR__orig_val__19030_19329);
}
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(sb__5795__auto__));
})():(format.cljs$core$IFn$_invoke$arity$2 ? format.cljs$core$IFn$_invoke$arity$2("%s\n",cause) : format.call(null,"%s\n",cause)));
return (format.cljs$core$IFn$_invoke$arity$4 ? format.cljs$core$IFn$_invoke$arity$4(G__19023,G__19024,G__19025,G__19026) : format.call(null,G__19023,G__19024,G__19025,G__19026));

break;
case "macroexpansion":
var G__19050 = "Unexpected error%s macroexpanding %sat (%s).\n%s\n";
var G__19051 = cause_type;
var G__19052 = (cljs.core.truth_(symbol)?(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(symbol)+" "):"");
var G__19053 = loc;
var G__19054 = cause;
return (format.cljs$core$IFn$_invoke$arity$5 ? format.cljs$core$IFn$_invoke$arity$5(G__19050,G__19051,G__19052,G__19053,G__19054) : format.call(null,G__19050,G__19051,G__19052,G__19053,G__19054));

break;
case "compile-syntax-check":
var G__19056 = "Syntax error%s compiling %sat (%s).\n%s\n";
var G__19057 = cause_type;
var G__19058 = (cljs.core.truth_(symbol)?(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(symbol)+" "):"");
var G__19059 = loc;
var G__19060 = cause;
return (format.cljs$core$IFn$_invoke$arity$5 ? format.cljs$core$IFn$_invoke$arity$5(G__19056,G__19057,G__19058,G__19059,G__19060) : format.call(null,G__19056,G__19057,G__19058,G__19059,G__19060));

break;
case "compilation":
var G__19064 = "Unexpected error%s compiling %sat (%s).\n%s\n";
var G__19065 = cause_type;
var G__19066 = (cljs.core.truth_(symbol)?(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(symbol)+" "):"");
var G__19067 = loc;
var G__19068 = cause;
return (format.cljs$core$IFn$_invoke$arity$5 ? format.cljs$core$IFn$_invoke$arity$5(G__19064,G__19065,G__19066,G__19067,G__19068) : format.call(null,G__19064,G__19065,G__19066,G__19067,G__19068));

break;
case "read-eval-result":
return (format.cljs$core$IFn$_invoke$arity$5 ? format.cljs$core$IFn$_invoke$arity$5("Error reading eval result%s at %s (%s).\n%s\n",cause_type,symbol,loc,cause) : format.call(null,"Error reading eval result%s at %s (%s).\n%s\n",cause_type,symbol,loc,cause));

break;
case "print-eval-result":
return (format.cljs$core$IFn$_invoke$arity$5 ? format.cljs$core$IFn$_invoke$arity$5("Error printing return value%s at %s (%s).\n%s\n",cause_type,symbol,loc,cause) : format.call(null,"Error printing return value%s at %s (%s).\n%s\n",cause_type,symbol,loc,cause));

break;
case "execution":
if(cljs.core.truth_(spec)){
var G__19079 = "Execution error - invalid arguments to %s at (%s).\n%s";
var G__19080 = symbol;
var G__19081 = loc;
var G__19082 = (function (){var sb__5795__auto__ = (new goog.string.StringBuffer());
var _STAR_print_newline_STAR__orig_val__19084_19344 = cljs.core._STAR_print_newline_STAR_;
var _STAR_print_fn_STAR__orig_val__19085_19345 = cljs.core._STAR_print_fn_STAR_;
var _STAR_print_newline_STAR__temp_val__19086_19346 = true;
var _STAR_print_fn_STAR__temp_val__19087_19347 = (function (x__5796__auto__){
return sb__5795__auto__.append(x__5796__auto__);
});
(cljs.core._STAR_print_newline_STAR_ = _STAR_print_newline_STAR__temp_val__19086_19346);

(cljs.core._STAR_print_fn_STAR_ = _STAR_print_fn_STAR__temp_val__19087_19347);

try{cljs.spec.alpha.explain_out(cljs.core.update.cljs$core$IFn$_invoke$arity$3(spec,new cljs.core.Keyword("cljs.spec.alpha","problems","cljs.spec.alpha/problems",447400814),(function (probs){
return cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p1__19005_SHARP_){
return cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(p1__19005_SHARP_,new cljs.core.Keyword(null,"in","in",-1531184865));
}),probs);
}))
);
}finally {(cljs.core._STAR_print_fn_STAR_ = _STAR_print_fn_STAR__orig_val__19085_19345);

(cljs.core._STAR_print_newline_STAR_ = _STAR_print_newline_STAR__orig_val__19084_19344);
}
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(sb__5795__auto__));
})();
return (format.cljs$core$IFn$_invoke$arity$4 ? format.cljs$core$IFn$_invoke$arity$4(G__19079,G__19080,G__19081,G__19082) : format.call(null,G__19079,G__19080,G__19081,G__19082));
} else {
var G__19101 = "Execution error%s at %s(%s).\n%s\n";
var G__19102 = cause_type;
var G__19103 = (cljs.core.truth_(symbol)?(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(symbol)+" "):"");
var G__19104 = loc;
var G__19105 = cause;
return (format.cljs$core$IFn$_invoke$arity$5 ? format.cljs$core$IFn$_invoke$arity$5(G__19101,G__19102,G__19103,G__19104,G__19105) : format.call(null,G__19101,G__19102,G__19103,G__19104,G__19105));
}

break;
default:
throw (new Error((""+"No matching clause: "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(G__19021__$1))));

}
});
cljs.repl.error__GT_str = (function cljs$repl$error__GT_str(error){
return cljs.repl.ex_str(cljs.repl.ex_triage(cljs.repl.Error__GT_map(error)));
});

//# sourceMappingURL=cljs.repl.js.map
