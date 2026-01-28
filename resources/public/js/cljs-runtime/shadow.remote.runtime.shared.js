goog.provide('shadow.remote.runtime.shared');
shadow.remote.runtime.shared.init_state = (function shadow$remote$runtime$shared$init_state(client_info){
return new cljs.core.PersistentArrayMap(null, 5, [new cljs.core.Keyword(null,"extensions","extensions",-1103629196),cljs.core.PersistentArrayMap.EMPTY,new cljs.core.Keyword(null,"ops","ops",1237330063),cljs.core.PersistentArrayMap.EMPTY,new cljs.core.Keyword(null,"client-info","client-info",1958982504),client_info,new cljs.core.Keyword(null,"call-id-seq","call-id-seq",-1679248218),(0),new cljs.core.Keyword(null,"call-handlers","call-handlers",386605551),cljs.core.PersistentArrayMap.EMPTY], null);
});
shadow.remote.runtime.shared.now = (function shadow$remote$runtime$shared$now(){
return Date.now();
});
shadow.remote.runtime.shared.get_client_id = (function shadow$remote$runtime$shared$get_client_id(p__14201){
var map__14202 = p__14201;
var map__14202__$1 = cljs.core.__destructure_map(map__14202);
var runtime = map__14202__$1;
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14202__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
var or__5142__auto__ = new cljs.core.Keyword(null,"client-id","client-id",-464622140).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(state_ref));
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
throw cljs.core.ex_info.cljs$core$IFn$_invoke$arity$2("runtime has no assigned runtime-id",new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"runtime","runtime",-1331573996),runtime], null));
}
});
shadow.remote.runtime.shared.relay_msg = (function shadow$remote$runtime$shared$relay_msg(runtime,msg){
var self_id_14399 = shadow.remote.runtime.shared.get_client_id(runtime);
if(cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"to","to",192099007).cljs$core$IFn$_invoke$arity$1(msg),self_id_14399)){
shadow.remote.runtime.api.relay_msg(runtime,msg);
} else {
Promise.resolve((1)).then((function (){
var G__14207 = runtime;
var G__14208 = cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(msg,new cljs.core.Keyword(null,"from","from",1815293044),self_id_14399);
return (shadow.remote.runtime.shared.process.cljs$core$IFn$_invoke$arity$2 ? shadow.remote.runtime.shared.process.cljs$core$IFn$_invoke$arity$2(G__14207,G__14208) : shadow.remote.runtime.shared.process.call(null,G__14207,G__14208));
}));
}

return msg;
});
shadow.remote.runtime.shared.reply = (function shadow$remote$runtime$shared$reply(runtime,p__14210,res){
var map__14211 = p__14210;
var map__14211__$1 = cljs.core.__destructure_map(map__14211);
var call_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14211__$1,new cljs.core.Keyword(null,"call-id","call-id",1043012968));
var from = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14211__$1,new cljs.core.Keyword(null,"from","from",1815293044));
var res__$1 = (function (){var G__14212 = res;
var G__14212__$1 = (cljs.core.truth_(call_id)?cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__14212,new cljs.core.Keyword(null,"call-id","call-id",1043012968),call_id):G__14212);
if(cljs.core.truth_(from)){
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(G__14212__$1,new cljs.core.Keyword(null,"to","to",192099007),from);
} else {
return G__14212__$1;
}
})();
return shadow.remote.runtime.api.relay_msg(runtime,res__$1);
});
shadow.remote.runtime.shared.call = (function shadow$remote$runtime$shared$call(var_args){
var G__14215 = arguments.length;
switch (G__14215) {
case 3:
return shadow.remote.runtime.shared.call.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
case 4:
return shadow.remote.runtime.shared.call.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.remote.runtime.shared.call.cljs$core$IFn$_invoke$arity$3 = (function (runtime,msg,handlers){
return shadow.remote.runtime.shared.call.cljs$core$IFn$_invoke$arity$4(runtime,msg,handlers,(0));
}));

(shadow.remote.runtime.shared.call.cljs$core$IFn$_invoke$arity$4 = (function (p__14217,msg,handlers,timeout_after_ms){
var map__14218 = p__14217;
var map__14218__$1 = cljs.core.__destructure_map(map__14218);
var runtime = map__14218__$1;
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14218__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
if(cljs.core.map_QMARK_(msg)){
} else {
throw (new Error("Assert failed: (map? msg)"));
}

if(cljs.core.map_QMARK_(handlers)){
} else {
throw (new Error("Assert failed: (map? handlers)"));
}

if(cljs.core.nat_int_QMARK_(timeout_after_ms)){
} else {
throw (new Error("Assert failed: (nat-int? timeout-after-ms)"));
}

var call_id = new cljs.core.Keyword(null,"call-id-seq","call-id-seq",-1679248218).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(state_ref));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(state_ref,cljs.core.update,new cljs.core.Keyword(null,"call-id-seq","call-id-seq",-1679248218),cljs.core.inc);

cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(state_ref,cljs.core.assoc_in,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"call-handlers","call-handlers",386605551),call_id], null),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"handlers","handlers",79528781),handlers,new cljs.core.Keyword(null,"called-at","called-at",607081160),shadow.remote.runtime.shared.now(),new cljs.core.Keyword(null,"msg","msg",-1386103444),msg,new cljs.core.Keyword(null,"timeout","timeout",-318625318),timeout_after_ms], null));

return shadow.remote.runtime.api.relay_msg(runtime,cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(msg,new cljs.core.Keyword(null,"call-id","call-id",1043012968),call_id));
}));

(shadow.remote.runtime.shared.call.cljs$lang$maxFixedArity = 4);

shadow.remote.runtime.shared.trigger_BANG_ = (function shadow$remote$runtime$shared$trigger_BANG_(var_args){
var args__5882__auto__ = [];
var len__5876__auto___14414 = arguments.length;
var i__5877__auto___14415 = (0);
while(true){
if((i__5877__auto___14415 < len__5876__auto___14414)){
args__5882__auto__.push((arguments[i__5877__auto___14415]));

var G__14416 = (i__5877__auto___14415 + (1));
i__5877__auto___14415 = G__14416;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((2) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((2)),(0),null)):null);
return shadow.remote.runtime.shared.trigger_BANG_.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),(arguments[(1)]),argseq__5883__auto__);
});

(shadow.remote.runtime.shared.trigger_BANG_.cljs$core$IFn$_invoke$arity$variadic = (function (p__14232,ev,args){
var map__14234 = p__14232;
var map__14234__$1 = cljs.core.__destructure_map(map__14234);
var runtime = map__14234__$1;
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14234__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
var seq__14235 = cljs.core.seq(cljs.core.vals(new cljs.core.Keyword(null,"extensions","extensions",-1103629196).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(state_ref))));
var chunk__14238 = null;
var count__14239 = (0);
var i__14240 = (0);
while(true){
if((i__14240 < count__14239)){
var ext = chunk__14238.cljs$core$IIndexed$_nth$arity$2(null,i__14240);
var ev_fn = cljs.core.get.cljs$core$IFn$_invoke$arity$2(ext,ev);
if(cljs.core.truth_(ev_fn)){
cljs.core.apply.cljs$core$IFn$_invoke$arity$2(ev_fn,args);


var G__14420 = seq__14235;
var G__14421 = chunk__14238;
var G__14422 = count__14239;
var G__14423 = (i__14240 + (1));
seq__14235 = G__14420;
chunk__14238 = G__14421;
count__14239 = G__14422;
i__14240 = G__14423;
continue;
} else {
var G__14424 = seq__14235;
var G__14425 = chunk__14238;
var G__14426 = count__14239;
var G__14427 = (i__14240 + (1));
seq__14235 = G__14424;
chunk__14238 = G__14425;
count__14239 = G__14426;
i__14240 = G__14427;
continue;
}
} else {
var temp__5823__auto__ = cljs.core.seq(seq__14235);
if(temp__5823__auto__){
var seq__14235__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__14235__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__14235__$1);
var G__14429 = cljs.core.chunk_rest(seq__14235__$1);
var G__14430 = c__5673__auto__;
var G__14431 = cljs.core.count(c__5673__auto__);
var G__14432 = (0);
seq__14235 = G__14429;
chunk__14238 = G__14430;
count__14239 = G__14431;
i__14240 = G__14432;
continue;
} else {
var ext = cljs.core.first(seq__14235__$1);
var ev_fn = cljs.core.get.cljs$core$IFn$_invoke$arity$2(ext,ev);
if(cljs.core.truth_(ev_fn)){
cljs.core.apply.cljs$core$IFn$_invoke$arity$2(ev_fn,args);


var G__14433 = cljs.core.next(seq__14235__$1);
var G__14434 = null;
var G__14435 = (0);
var G__14436 = (0);
seq__14235 = G__14433;
chunk__14238 = G__14434;
count__14239 = G__14435;
i__14240 = G__14436;
continue;
} else {
var G__14437 = cljs.core.next(seq__14235__$1);
var G__14438 = null;
var G__14439 = (0);
var G__14440 = (0);
seq__14235 = G__14437;
chunk__14238 = G__14438;
count__14239 = G__14439;
i__14240 = G__14440;
continue;
}
}
} else {
return null;
}
}
break;
}
}));

(shadow.remote.runtime.shared.trigger_BANG_.cljs$lang$maxFixedArity = (2));

/** @this {Function} */
(shadow.remote.runtime.shared.trigger_BANG_.cljs$lang$applyTo = (function (seq14219){
var G__14220 = cljs.core.first(seq14219);
var seq14219__$1 = cljs.core.next(seq14219);
var G__14221 = cljs.core.first(seq14219__$1);
var seq14219__$2 = cljs.core.next(seq14219__$1);
var self__5861__auto__ = this;
return self__5861__auto__.cljs$core$IFn$_invoke$arity$variadic(G__14220,G__14221,seq14219__$2);
}));

shadow.remote.runtime.shared.welcome = (function shadow$remote$runtime$shared$welcome(p__14255,p__14256){
var map__14257 = p__14255;
var map__14257__$1 = cljs.core.__destructure_map(map__14257);
var runtime = map__14257__$1;
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14257__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
var map__14258 = p__14256;
var map__14258__$1 = cljs.core.__destructure_map(map__14258);
var msg = map__14258__$1;
var client_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14258__$1,new cljs.core.Keyword(null,"client-id","client-id",-464622140));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$variadic(state_ref,cljs.core.assoc,new cljs.core.Keyword(null,"client-id","client-id",-464622140),client_id,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"welcome","welcome",-578152123),true], 0));

var map__14260 = cljs.core.deref(state_ref);
var map__14260__$1 = cljs.core.__destructure_map(map__14260);
var client_info = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14260__$1,new cljs.core.Keyword(null,"client-info","client-info",1958982504));
var extensions = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14260__$1,new cljs.core.Keyword(null,"extensions","extensions",-1103629196));
shadow.remote.runtime.shared.relay_msg(runtime,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"hello","hello",-245025397),new cljs.core.Keyword(null,"client-info","client-info",1958982504),client_info], null));

return shadow.remote.runtime.shared.trigger_BANG_(runtime,new cljs.core.Keyword(null,"on-welcome","on-welcome",1895317125));
});
shadow.remote.runtime.shared.ping = (function shadow$remote$runtime$shared$ping(runtime,msg){
return shadow.remote.runtime.shared.reply(runtime,msg,new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"pong","pong",-172484958)], null));
});
shadow.remote.runtime.shared.request_supported_ops = (function shadow$remote$runtime$shared$request_supported_ops(p__14268,msg){
var map__14270 = p__14268;
var map__14270__$1 = cljs.core.__destructure_map(map__14270);
var runtime = map__14270__$1;
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14270__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
return shadow.remote.runtime.shared.reply(runtime,msg,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"supported-ops","supported-ops",337914702),new cljs.core.Keyword(null,"ops","ops",1237330063),cljs.core.disj.cljs$core$IFn$_invoke$arity$variadic(cljs.core.set(cljs.core.keys(new cljs.core.Keyword(null,"ops","ops",1237330063).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(state_ref)))),new cljs.core.Keyword(null,"welcome","welcome",-578152123),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"unknown-relay-op","unknown-relay-op",170832753),new cljs.core.Keyword(null,"unknown-op","unknown-op",1900385996),new cljs.core.Keyword(null,"request-supported-ops","request-supported-ops",-1034994502),new cljs.core.Keyword(null,"tool-disconnect","tool-disconnect",189103996)], 0))], null));
});
shadow.remote.runtime.shared.unknown_relay_op = (function shadow$remote$runtime$shared$unknown_relay_op(msg){
return console.warn("unknown-relay-op",msg);
});
shadow.remote.runtime.shared.unknown_op = (function shadow$remote$runtime$shared$unknown_op(msg){
return console.warn("unknown-op",msg);
});
shadow.remote.runtime.shared.add_extension_STAR_ = (function shadow$remote$runtime$shared$add_extension_STAR_(p__14288,key,p__14289){
var map__14292 = p__14288;
var map__14292__$1 = cljs.core.__destructure_map(map__14292);
var state = map__14292__$1;
var extensions = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14292__$1,new cljs.core.Keyword(null,"extensions","extensions",-1103629196));
var map__14296 = p__14289;
var map__14296__$1 = cljs.core.__destructure_map(map__14296);
var spec = map__14296__$1;
var ops = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14296__$1,new cljs.core.Keyword(null,"ops","ops",1237330063));
var transit_write_handlers = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14296__$1,new cljs.core.Keyword(null,"transit-write-handlers","transit-write-handlers",1886308716));
if(cljs.core.contains_QMARK_(extensions,key)){
throw cljs.core.ex_info.cljs$core$IFn$_invoke$arity$2("extension already registered",new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"key","key",-1516042587),key,new cljs.core.Keyword(null,"spec","spec",347520401),spec], null));
} else {
}

return cljs.core.reduce_kv((function (state__$1,op_kw,op_handler){
if(cljs.core.truth_(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(state__$1,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ops","ops",1237330063),op_kw], null)))){
throw cljs.core.ex_info.cljs$core$IFn$_invoke$arity$2("op already registered",new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"key","key",-1516042587),key,new cljs.core.Keyword(null,"op","op",-1882987955),op_kw], null));
} else {
}

return cljs.core.assoc_in(state__$1,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ops","ops",1237330063),op_kw], null),op_handler);
}),cljs.core.assoc_in(state,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"extensions","extensions",-1103629196),key], null),spec),ops);
});
shadow.remote.runtime.shared.add_extension = (function shadow$remote$runtime$shared$add_extension(p__14303,key,spec){
var map__14304 = p__14303;
var map__14304__$1 = cljs.core.__destructure_map(map__14304);
var runtime = map__14304__$1;
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14304__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(state_ref,shadow.remote.runtime.shared.add_extension_STAR_,key,spec);

var temp__5827__auto___14467 = new cljs.core.Keyword(null,"on-welcome","on-welcome",1895317125).cljs$core$IFn$_invoke$arity$1(spec);
if((temp__5827__auto___14467 == null)){
} else {
var on_welcome_14468 = temp__5827__auto___14467;
if(cljs.core.truth_(new cljs.core.Keyword(null,"welcome","welcome",-578152123).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(state_ref)))){
(on_welcome_14468.cljs$core$IFn$_invoke$arity$0 ? on_welcome_14468.cljs$core$IFn$_invoke$arity$0() : on_welcome_14468.call(null));
} else {
}
}

return runtime;
});
shadow.remote.runtime.shared.add_defaults = (function shadow$remote$runtime$shared$add_defaults(runtime){
return shadow.remote.runtime.shared.add_extension(runtime,new cljs.core.Keyword("shadow.remote.runtime.shared","defaults","shadow.remote.runtime.shared/defaults",-1821257543),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"ops","ops",1237330063),new cljs.core.PersistentArrayMap(null, 5, [new cljs.core.Keyword(null,"welcome","welcome",-578152123),(function (p1__14311_SHARP_){
return shadow.remote.runtime.shared.welcome(runtime,p1__14311_SHARP_);
}),new cljs.core.Keyword(null,"unknown-relay-op","unknown-relay-op",170832753),(function (p1__14312_SHARP_){
return shadow.remote.runtime.shared.unknown_relay_op(p1__14312_SHARP_);
}),new cljs.core.Keyword(null,"unknown-op","unknown-op",1900385996),(function (p1__14313_SHARP_){
return shadow.remote.runtime.shared.unknown_op(p1__14313_SHARP_);
}),new cljs.core.Keyword(null,"ping","ping",-1670114784),(function (p1__14314_SHARP_){
return shadow.remote.runtime.shared.ping(runtime,p1__14314_SHARP_);
}),new cljs.core.Keyword(null,"request-supported-ops","request-supported-ops",-1034994502),(function (p1__14315_SHARP_){
return shadow.remote.runtime.shared.request_supported_ops(runtime,p1__14315_SHARP_);
})], null)], null));
});
shadow.remote.runtime.shared.del_extension_STAR_ = (function shadow$remote$runtime$shared$del_extension_STAR_(state,key){
var ext = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(state,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"extensions","extensions",-1103629196),key], null));
if(cljs.core.not(ext)){
return state;
} else {
return cljs.core.reduce_kv((function (state__$1,op_kw,op_handler){
return cljs.core.update_in.cljs$core$IFn$_invoke$arity$4(state__$1,new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ops","ops",1237330063)], null),cljs.core.dissoc,op_kw);
}),cljs.core.update.cljs$core$IFn$_invoke$arity$4(state,new cljs.core.Keyword(null,"extensions","extensions",-1103629196),cljs.core.dissoc,key),new cljs.core.Keyword(null,"ops","ops",1237330063).cljs$core$IFn$_invoke$arity$1(ext));
}
});
shadow.remote.runtime.shared.del_extension = (function shadow$remote$runtime$shared$del_extension(p__14340,key){
var map__14345 = p__14340;
var map__14345__$1 = cljs.core.__destructure_map(map__14345);
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14345__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(state_ref,shadow.remote.runtime.shared.del_extension_STAR_,key);
});
shadow.remote.runtime.shared.unhandled_call_result = (function shadow$remote$runtime$shared$unhandled_call_result(call_config,msg){
return console.warn("unhandled call result",msg,call_config);
});
shadow.remote.runtime.shared.unhandled_client_not_found = (function shadow$remote$runtime$shared$unhandled_client_not_found(p__14348,msg){
var map__14349 = p__14348;
var map__14349__$1 = cljs.core.__destructure_map(map__14349);
var runtime = map__14349__$1;
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14349__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
return shadow.remote.runtime.shared.trigger_BANG_.cljs$core$IFn$_invoke$arity$variadic(runtime,new cljs.core.Keyword(null,"on-client-not-found","on-client-not-found",-642452849),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([msg], 0));
});
shadow.remote.runtime.shared.reply_unknown_op = (function shadow$remote$runtime$shared$reply_unknown_op(runtime,msg){
return shadow.remote.runtime.shared.reply(runtime,msg,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"unknown-op","unknown-op",1900385996),new cljs.core.Keyword(null,"msg","msg",-1386103444),msg], null));
});
shadow.remote.runtime.shared.process = (function shadow$remote$runtime$shared$process(p__14366,p__14367){
var map__14368 = p__14366;
var map__14368__$1 = cljs.core.__destructure_map(map__14368);
var runtime = map__14368__$1;
var state_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14368__$1,new cljs.core.Keyword(null,"state-ref","state-ref",2127874952));
var map__14369 = p__14367;
var map__14369__$1 = cljs.core.__destructure_map(map__14369);
var msg = map__14369__$1;
var op = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14369__$1,new cljs.core.Keyword(null,"op","op",-1882987955));
var call_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14369__$1,new cljs.core.Keyword(null,"call-id","call-id",1043012968));
var state = cljs.core.deref(state_ref);
var op_handler = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(state,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"ops","ops",1237330063),op], null));
if(cljs.core.truth_(call_id)){
var cfg = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(state,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"call-handlers","call-handlers",386605551),call_id], null));
var call_handler = cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(cfg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"handlers","handlers",79528781),op], null));
if(cljs.core.truth_(call_handler)){
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$variadic(state_ref,cljs.core.update,new cljs.core.Keyword(null,"call-handlers","call-handlers",386605551),cljs.core.dissoc,cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([call_id], 0));

return (call_handler.cljs$core$IFn$_invoke$arity$1 ? call_handler.cljs$core$IFn$_invoke$arity$1(msg) : call_handler.call(null,msg));
} else {
if(cljs.core.truth_(op_handler)){
return (op_handler.cljs$core$IFn$_invoke$arity$1 ? op_handler.cljs$core$IFn$_invoke$arity$1(msg) : op_handler.call(null,msg));
} else {
return shadow.remote.runtime.shared.unhandled_call_result(cfg,msg);

}
}
} else {
if(cljs.core.truth_(op_handler)){
return (op_handler.cljs$core$IFn$_invoke$arity$1 ? op_handler.cljs$core$IFn$_invoke$arity$1(msg) : op_handler.call(null,msg));
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"client-not-found","client-not-found",-1754042614),op)){
return shadow.remote.runtime.shared.unhandled_client_not_found(runtime,msg);
} else {
return shadow.remote.runtime.shared.reply_unknown_op(runtime,msg);

}
}
}
});
shadow.remote.runtime.shared.run_on_idle = (function shadow$remote$runtime$shared$run_on_idle(state_ref){
var seq__14380 = cljs.core.seq(cljs.core.vals(new cljs.core.Keyword(null,"extensions","extensions",-1103629196).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(state_ref))));
var chunk__14382 = null;
var count__14383 = (0);
var i__14384 = (0);
while(true){
if((i__14384 < count__14383)){
var map__14392 = chunk__14382.cljs$core$IIndexed$_nth$arity$2(null,i__14384);
var map__14392__$1 = cljs.core.__destructure_map(map__14392);
var on_idle = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14392__$1,new cljs.core.Keyword(null,"on-idle","on-idle",2044706602));
if(cljs.core.truth_(on_idle)){
(on_idle.cljs$core$IFn$_invoke$arity$0 ? on_idle.cljs$core$IFn$_invoke$arity$0() : on_idle.call(null));


var G__14535 = seq__14380;
var G__14536 = chunk__14382;
var G__14537 = count__14383;
var G__14538 = (i__14384 + (1));
seq__14380 = G__14535;
chunk__14382 = G__14536;
count__14383 = G__14537;
i__14384 = G__14538;
continue;
} else {
var G__14539 = seq__14380;
var G__14540 = chunk__14382;
var G__14541 = count__14383;
var G__14542 = (i__14384 + (1));
seq__14380 = G__14539;
chunk__14382 = G__14540;
count__14383 = G__14541;
i__14384 = G__14542;
continue;
}
} else {
var temp__5823__auto__ = cljs.core.seq(seq__14380);
if(temp__5823__auto__){
var seq__14380__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__14380__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__14380__$1);
var G__14554 = cljs.core.chunk_rest(seq__14380__$1);
var G__14555 = c__5673__auto__;
var G__14556 = cljs.core.count(c__5673__auto__);
var G__14557 = (0);
seq__14380 = G__14554;
chunk__14382 = G__14555;
count__14383 = G__14556;
i__14384 = G__14557;
continue;
} else {
var map__14394 = cljs.core.first(seq__14380__$1);
var map__14394__$1 = cljs.core.__destructure_map(map__14394);
var on_idle = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__14394__$1,new cljs.core.Keyword(null,"on-idle","on-idle",2044706602));
if(cljs.core.truth_(on_idle)){
(on_idle.cljs$core$IFn$_invoke$arity$0 ? on_idle.cljs$core$IFn$_invoke$arity$0() : on_idle.call(null));


var G__14574 = cljs.core.next(seq__14380__$1);
var G__14575 = null;
var G__14576 = (0);
var G__14577 = (0);
seq__14380 = G__14574;
chunk__14382 = G__14575;
count__14383 = G__14576;
i__14384 = G__14577;
continue;
} else {
var G__14585 = cljs.core.next(seq__14380__$1);
var G__14586 = null;
var G__14587 = (0);
var G__14588 = (0);
seq__14380 = G__14585;
chunk__14382 = G__14586;
count__14383 = G__14587;
i__14384 = G__14588;
continue;
}
}
} else {
return null;
}
}
break;
}
});

//# sourceMappingURL=shadow.remote.runtime.shared.js.map
