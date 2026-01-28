goog.provide('re_frame.fx');
re_frame.fx.kind = new cljs.core.Keyword(null,"fx","fx",-1237829572);
if(cljs.core.truth_((re_frame.registrar.kinds.cljs$core$IFn$_invoke$arity$1 ? re_frame.registrar.kinds.cljs$core$IFn$_invoke$arity$1(re_frame.fx.kind) : re_frame.registrar.kinds.call(null,re_frame.fx.kind)))){
} else {
throw (new Error("Assert failed: (re-frame.registrar/kinds kind)"));
}
re_frame.fx.reg_fx = (function re_frame$fx$reg_fx(id,handler){
return re_frame.registrar.register_handler(re_frame.fx.kind,id,handler);
});
/**
 * An interceptor whose `:after` actions the contents of `:effects`. As a result,
 *   this interceptor is Domino 3.
 * 
 *   This interceptor is silently added (by reg-event-db etc) to the front of
 *   interceptor chains for all events.
 * 
 *   For each key in `:effects` (a map), it calls the registered `effects handler`
 *   (see `reg-fx` for registration of effect handlers).
 * 
 *   So, if `:effects` was:
 *    {:dispatch  [:hello 42]
 *     :db        {...}
 *     :undo      "set flag"}
 * 
 *   it will call the registered effect handlers for each of the map's keys:
 *   `:dispatch`, `:undo` and `:db`. When calling each handler, provides the map
 *   value for that key - so in the example above the effect handler for :dispatch
 *   will be given one arg `[:hello 42]`.
 * 
 *   You cannot rely on the ordering in which effects are executed, other than that
 *   `:db` is guaranteed to be executed first.
 */
re_frame.fx.do_fx = re_frame.interceptor.__GT_interceptor.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"id","id",-1388402092),new cljs.core.Keyword(null,"do-fx","do-fx",1194163050),new cljs.core.Keyword(null,"after","after",594996914),(function re_frame$fx$do_fx_after(context){
if(re_frame.trace.is_trace_enabled_QMARK_()){
var _STAR_current_trace_STAR__orig_val__23087 = re_frame.trace._STAR_current_trace_STAR_;
var _STAR_current_trace_STAR__temp_val__23088 = re_frame.trace.start_trace(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"op-type","op-type",-1636141668),new cljs.core.Keyword("event","do-fx","event/do-fx",1357330452)], null));
(re_frame.trace._STAR_current_trace_STAR_ = _STAR_current_trace_STAR__temp_val__23088);

try{try{var effects = new cljs.core.Keyword(null,"effects","effects",-282369292).cljs$core$IFn$_invoke$arity$1(context);
var effects_without_db = cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(effects,new cljs.core.Keyword(null,"db","db",993250759));
var temp__5823__auto___23180 = new cljs.core.Keyword(null,"db","db",993250759).cljs$core$IFn$_invoke$arity$1(effects);
if(cljs.core.truth_(temp__5823__auto___23180)){
var new_db_23181 = temp__5823__auto___23180;
var fexpr__23091_23182 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,new cljs.core.Keyword(null,"db","db",993250759),false);
(fexpr__23091_23182.cljs$core$IFn$_invoke$arity$1 ? fexpr__23091_23182.cljs$core$IFn$_invoke$arity$1(new_db_23181) : fexpr__23091_23182.call(null,new_db_23181));
} else {
}

var seq__23093 = cljs.core.seq(effects_without_db);
var chunk__23094 = null;
var count__23095 = (0);
var i__23096 = (0);
while(true){
if((i__23096 < count__23095)){
var vec__23105 = chunk__23094.cljs$core$IIndexed$_nth$arity$2(null,i__23096);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23105,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23105,(1),null);
var temp__5821__auto___23183 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___23183)){
var effect_fn_23184 = temp__5821__auto___23183;
(effect_fn_23184.cljs$core$IFn$_invoke$arity$1 ? effect_fn_23184.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_23184.call(null,effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: no handler registered for effect:",effect_key,". Ignoring.",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"event","event",301435442),effect_key))?(""+"You may be trying to return a coeffect map from an event-fx handler. "+"See https://day8.github.io/re-frame/use-cofx-as-fx/"):null)], 0));
}


var G__23185 = seq__23093;
var G__23186 = chunk__23094;
var G__23187 = count__23095;
var G__23188 = (i__23096 + (1));
seq__23093 = G__23185;
chunk__23094 = G__23186;
count__23095 = G__23187;
i__23096 = G__23188;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__23093);
if(temp__5823__auto__){
var seq__23093__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__23093__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__23093__$1);
var G__23189 = cljs.core.chunk_rest(seq__23093__$1);
var G__23190 = c__5673__auto__;
var G__23191 = cljs.core.count(c__5673__auto__);
var G__23192 = (0);
seq__23093 = G__23189;
chunk__23094 = G__23190;
count__23095 = G__23191;
i__23096 = G__23192;
continue;
} else {
var vec__23109 = cljs.core.first(seq__23093__$1);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23109,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23109,(1),null);
var temp__5821__auto___23193 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___23193)){
var effect_fn_23194 = temp__5821__auto___23193;
(effect_fn_23194.cljs$core$IFn$_invoke$arity$1 ? effect_fn_23194.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_23194.call(null,effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: no handler registered for effect:",effect_key,". Ignoring.",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"event","event",301435442),effect_key))?(""+"You may be trying to return a coeffect map from an event-fx handler. "+"See https://day8.github.io/re-frame/use-cofx-as-fx/"):null)], 0));
}


var G__23195 = cljs.core.next(seq__23093__$1);
var G__23196 = null;
var G__23197 = (0);
var G__23198 = (0);
seq__23093 = G__23195;
chunk__23094 = G__23196;
count__23095 = G__23197;
i__23096 = G__23198;
continue;
}
} else {
return null;
}
}
break;
}
}finally {if(re_frame.trace.is_trace_enabled_QMARK_()){
var end__22691__auto___23199 = re_frame.interop.now();
var duration__22692__auto___23200 = (end__22691__auto___23199 - new cljs.core.Keyword(null,"start","start",-355208981).cljs$core$IFn$_invoke$arity$1(re_frame.trace._STAR_current_trace_STAR_));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(re_frame.trace.traces,cljs.core.conj,cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(re_frame.trace._STAR_current_trace_STAR_,new cljs.core.Keyword(null,"duration","duration",1444101068),duration__22692__auto___23200,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"end","end",-268185958),re_frame.interop.now()], 0)));

re_frame.trace.run_tracing_callbacks_BANG_(end__22691__auto___23199);
} else {
}
}}finally {(re_frame.trace._STAR_current_trace_STAR_ = _STAR_current_trace_STAR__orig_val__23087);
}} else {
var effects = new cljs.core.Keyword(null,"effects","effects",-282369292).cljs$core$IFn$_invoke$arity$1(context);
var effects_without_db = cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(effects,new cljs.core.Keyword(null,"db","db",993250759));
var temp__5823__auto___23201 = new cljs.core.Keyword(null,"db","db",993250759).cljs$core$IFn$_invoke$arity$1(effects);
if(cljs.core.truth_(temp__5823__auto___23201)){
var new_db_23202 = temp__5823__auto___23201;
var fexpr__23114_23203 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,new cljs.core.Keyword(null,"db","db",993250759),false);
(fexpr__23114_23203.cljs$core$IFn$_invoke$arity$1 ? fexpr__23114_23203.cljs$core$IFn$_invoke$arity$1(new_db_23202) : fexpr__23114_23203.call(null,new_db_23202));
} else {
}

var seq__23115 = cljs.core.seq(effects_without_db);
var chunk__23116 = null;
var count__23117 = (0);
var i__23118 = (0);
while(true){
if((i__23118 < count__23117)){
var vec__23126 = chunk__23116.cljs$core$IIndexed$_nth$arity$2(null,i__23118);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23126,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23126,(1),null);
var temp__5821__auto___23204 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___23204)){
var effect_fn_23205 = temp__5821__auto___23204;
(effect_fn_23205.cljs$core$IFn$_invoke$arity$1 ? effect_fn_23205.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_23205.call(null,effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: no handler registered for effect:",effect_key,". Ignoring.",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"event","event",301435442),effect_key))?(""+"You may be trying to return a coeffect map from an event-fx handler. "+"See https://day8.github.io/re-frame/use-cofx-as-fx/"):null)], 0));
}


var G__23206 = seq__23115;
var G__23207 = chunk__23116;
var G__23208 = count__23117;
var G__23209 = (i__23118 + (1));
seq__23115 = G__23206;
chunk__23116 = G__23207;
count__23117 = G__23208;
i__23118 = G__23209;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__23115);
if(temp__5823__auto__){
var seq__23115__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__23115__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__23115__$1);
var G__23210 = cljs.core.chunk_rest(seq__23115__$1);
var G__23211 = c__5673__auto__;
var G__23212 = cljs.core.count(c__5673__auto__);
var G__23213 = (0);
seq__23115 = G__23210;
chunk__23116 = G__23211;
count__23117 = G__23212;
i__23118 = G__23213;
continue;
} else {
var vec__23141 = cljs.core.first(seq__23115__$1);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23141,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23141,(1),null);
var temp__5821__auto___23214 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___23214)){
var effect_fn_23215 = temp__5821__auto___23214;
(effect_fn_23215.cljs$core$IFn$_invoke$arity$1 ? effect_fn_23215.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_23215.call(null,effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: no handler registered for effect:",effect_key,". Ignoring.",((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"event","event",301435442),effect_key))?(""+"You may be trying to return a coeffect map from an event-fx handler. "+"See https://day8.github.io/re-frame/use-cofx-as-fx/"):null)], 0));
}


var G__23216 = cljs.core.next(seq__23115__$1);
var G__23217 = null;
var G__23218 = (0);
var G__23219 = (0);
seq__23115 = G__23216;
chunk__23116 = G__23217;
count__23117 = G__23218;
i__23118 = G__23219;
continue;
}
} else {
return null;
}
}
break;
}
}
})], 0));
re_frame.fx.dispatch_later = (function re_frame$fx$dispatch_later(p__23148){
var map__23149 = p__23148;
var map__23149__$1 = cljs.core.__destructure_map(map__23149);
var effect = map__23149__$1;
var ms = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__23149__$1,new cljs.core.Keyword(null,"ms","ms",-1152709733));
var dispatch = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__23149__$1,new cljs.core.Keyword(null,"dispatch","dispatch",1319337009));
if(((cljs.core.empty_QMARK_(dispatch)) || ((!(typeof ms === 'number'))))){
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: ignoring bad :dispatch-later value:",effect], 0));
} else {
return re_frame.interop.set_timeout_BANG_((function (){
return re_frame.router.dispatch(dispatch);
}),ms);
}
});
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"dispatch-later","dispatch-later",291951390),(function (value){
if(cljs.core.map_QMARK_(value)){
return re_frame.fx.dispatch_later(value);
} else {
var seq__23150 = cljs.core.seq(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(cljs.core.nil_QMARK_,value));
var chunk__23151 = null;
var count__23152 = (0);
var i__23153 = (0);
while(true){
if((i__23153 < count__23152)){
var effect = chunk__23151.cljs$core$IIndexed$_nth$arity$2(null,i__23153);
re_frame.fx.dispatch_later(effect);


var G__23220 = seq__23150;
var G__23221 = chunk__23151;
var G__23222 = count__23152;
var G__23223 = (i__23153 + (1));
seq__23150 = G__23220;
chunk__23151 = G__23221;
count__23152 = G__23222;
i__23153 = G__23223;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__23150);
if(temp__5823__auto__){
var seq__23150__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__23150__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__23150__$1);
var G__23224 = cljs.core.chunk_rest(seq__23150__$1);
var G__23225 = c__5673__auto__;
var G__23226 = cljs.core.count(c__5673__auto__);
var G__23227 = (0);
seq__23150 = G__23224;
chunk__23151 = G__23225;
count__23152 = G__23226;
i__23153 = G__23227;
continue;
} else {
var effect = cljs.core.first(seq__23150__$1);
re_frame.fx.dispatch_later(effect);


var G__23228 = cljs.core.next(seq__23150__$1);
var G__23229 = null;
var G__23230 = (0);
var G__23231 = (0);
seq__23150 = G__23228;
chunk__23151 = G__23229;
count__23152 = G__23230;
i__23153 = G__23231;
continue;
}
} else {
return null;
}
}
break;
}
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"fx","fx",-1237829572),(function (seq_of_effects){
if((!(cljs.core.sequential_QMARK_(seq_of_effects)))){
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: \":fx\" effect expects a seq, but was given ",cljs.core.type(seq_of_effects)], 0));
} else {
var seq__23154 = cljs.core.seq(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(cljs.core.nil_QMARK_,seq_of_effects));
var chunk__23155 = null;
var count__23156 = (0);
var i__23157 = (0);
while(true){
if((i__23157 < count__23156)){
var vec__23164 = chunk__23155.cljs$core$IIndexed$_nth$arity$2(null,i__23157);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23164,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23164,(1),null);
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"db","db",993250759),effect_key)){
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: \":fx\" effect should not contain a :db effect"], 0));
} else {
}

var temp__5821__auto___23233 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___23233)){
var effect_fn_23234 = temp__5821__auto___23233;
(effect_fn_23234.cljs$core$IFn$_invoke$arity$1 ? effect_fn_23234.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_23234.call(null,effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: in \":fx\" effect found ",effect_key," which has no associated handler. Ignoring."], 0));
}


var G__23236 = seq__23154;
var G__23237 = chunk__23155;
var G__23238 = count__23156;
var G__23239 = (i__23157 + (1));
seq__23154 = G__23236;
chunk__23155 = G__23237;
count__23156 = G__23238;
i__23157 = G__23239;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__23154);
if(temp__5823__auto__){
var seq__23154__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__23154__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__23154__$1);
var G__23240 = cljs.core.chunk_rest(seq__23154__$1);
var G__23241 = c__5673__auto__;
var G__23242 = cljs.core.count(c__5673__auto__);
var G__23243 = (0);
seq__23154 = G__23240;
chunk__23155 = G__23241;
count__23156 = G__23242;
i__23157 = G__23243;
continue;
} else {
var vec__23167 = cljs.core.first(seq__23154__$1);
var effect_key = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23167,(0),null);
var effect_value = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__23167,(1),null);
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"db","db",993250759),effect_key)){
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: \":fx\" effect should not contain a :db effect"], 0));
} else {
}

var temp__5821__auto___23244 = re_frame.registrar.get_handler.cljs$core$IFn$_invoke$arity$3(re_frame.fx.kind,effect_key,false);
if(cljs.core.truth_(temp__5821__auto___23244)){
var effect_fn_23245 = temp__5821__auto___23244;
(effect_fn_23245.cljs$core$IFn$_invoke$arity$1 ? effect_fn_23245.cljs$core$IFn$_invoke$arity$1(effect_value) : effect_fn_23245.call(null,effect_value));
} else {
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: in \":fx\" effect found ",effect_key," which has no associated handler. Ignoring."], 0));
}


var G__23246 = cljs.core.next(seq__23154__$1);
var G__23247 = null;
var G__23248 = (0);
var G__23249 = (0);
seq__23154 = G__23246;
chunk__23155 = G__23247;
count__23156 = G__23248;
i__23157 = G__23249;
continue;
}
} else {
return null;
}
}
break;
}
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"dispatch","dispatch",1319337009),(function (value){
if((!(cljs.core.vector_QMARK_(value)))){
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: ignoring bad :dispatch value. Expected a vector, but got:",value], 0));
} else {
return re_frame.router.dispatch(value);
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"dispatch-n","dispatch-n",-504469236),(function (value){
if((!(cljs.core.sequential_QMARK_(value)))){
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["re-frame: ignoring bad :dispatch-n value. Expected a collection, but got:",value], 0));
} else {
var seq__23170 = cljs.core.seq(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(cljs.core.nil_QMARK_,value));
var chunk__23171 = null;
var count__23172 = (0);
var i__23173 = (0);
while(true){
if((i__23173 < count__23172)){
var event = chunk__23171.cljs$core$IIndexed$_nth$arity$2(null,i__23173);
re_frame.router.dispatch(event);


var G__23250 = seq__23170;
var G__23251 = chunk__23171;
var G__23252 = count__23172;
var G__23253 = (i__23173 + (1));
seq__23170 = G__23250;
chunk__23171 = G__23251;
count__23172 = G__23252;
i__23173 = G__23253;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__23170);
if(temp__5823__auto__){
var seq__23170__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__23170__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__23170__$1);
var G__23254 = cljs.core.chunk_rest(seq__23170__$1);
var G__23255 = c__5673__auto__;
var G__23256 = cljs.core.count(c__5673__auto__);
var G__23257 = (0);
seq__23170 = G__23254;
chunk__23171 = G__23255;
count__23172 = G__23256;
i__23173 = G__23257;
continue;
} else {
var event = cljs.core.first(seq__23170__$1);
re_frame.router.dispatch(event);


var G__23258 = cljs.core.next(seq__23170__$1);
var G__23259 = null;
var G__23260 = (0);
var G__23261 = (0);
seq__23170 = G__23258;
chunk__23171 = G__23259;
count__23172 = G__23260;
i__23173 = G__23261;
continue;
}
} else {
return null;
}
}
break;
}
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"deregister-event-handler","deregister-event-handler",-1096518994),(function (value){
var clear_event = cljs.core.partial.cljs$core$IFn$_invoke$arity$2(re_frame.registrar.clear_handlers,re_frame.events.kind);
if(cljs.core.sequential_QMARK_(value)){
var seq__23174 = cljs.core.seq(value);
var chunk__23175 = null;
var count__23176 = (0);
var i__23177 = (0);
while(true){
if((i__23177 < count__23176)){
var event = chunk__23175.cljs$core$IIndexed$_nth$arity$2(null,i__23177);
clear_event(event);


var G__23264 = seq__23174;
var G__23265 = chunk__23175;
var G__23266 = count__23176;
var G__23267 = (i__23177 + (1));
seq__23174 = G__23264;
chunk__23175 = G__23265;
count__23176 = G__23266;
i__23177 = G__23267;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__23174);
if(temp__5823__auto__){
var seq__23174__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__23174__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__23174__$1);
var G__23268 = cljs.core.chunk_rest(seq__23174__$1);
var G__23269 = c__5673__auto__;
var G__23270 = cljs.core.count(c__5673__auto__);
var G__23271 = (0);
seq__23174 = G__23268;
chunk__23175 = G__23269;
count__23176 = G__23270;
i__23177 = G__23271;
continue;
} else {
var event = cljs.core.first(seq__23174__$1);
clear_event(event);


var G__23272 = cljs.core.next(seq__23174__$1);
var G__23273 = null;
var G__23274 = (0);
var G__23275 = (0);
seq__23174 = G__23272;
chunk__23175 = G__23273;
count__23176 = G__23274;
i__23177 = G__23275;
continue;
}
} else {
return null;
}
}
break;
}
} else {
return clear_event(value);
}
}));
re_frame.fx.reg_fx(new cljs.core.Keyword(null,"db","db",993250759),(function (value){
if((!((cljs.core.deref(re_frame.db.app_db) === value)))){
return cljs.core.reset_BANG_(re_frame.db.app_db,value);
} else {
if(re_frame.trace.is_trace_enabled_QMARK_()){
var _STAR_current_trace_STAR__orig_val__23178 = re_frame.trace._STAR_current_trace_STAR_;
var _STAR_current_trace_STAR__temp_val__23179 = re_frame.trace.start_trace(new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"op-type","op-type",-1636141668),new cljs.core.Keyword("reagent","quiescent","reagent/quiescent",-16138681)], null));
(re_frame.trace._STAR_current_trace_STAR_ = _STAR_current_trace_STAR__temp_val__23179);

try{try{return null;
}finally {if(re_frame.trace.is_trace_enabled_QMARK_()){
var end__22691__auto___23276 = re_frame.interop.now();
var duration__22692__auto___23277 = (end__22691__auto___23276 - new cljs.core.Keyword(null,"start","start",-355208981).cljs$core$IFn$_invoke$arity$1(re_frame.trace._STAR_current_trace_STAR_));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(re_frame.trace.traces,cljs.core.conj,cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(re_frame.trace._STAR_current_trace_STAR_,new cljs.core.Keyword(null,"duration","duration",1444101068),duration__22692__auto___23277,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"end","end",-268185958),re_frame.interop.now()], 0)));

re_frame.trace.run_tracing_callbacks_BANG_(end__22691__auto___23276);
} else {
}
}}finally {(re_frame.trace._STAR_current_trace_STAR_ = _STAR_current_trace_STAR__orig_val__23178);
}} else {
return null;
}
}
}));

//# sourceMappingURL=re_frame.fx.js.map
