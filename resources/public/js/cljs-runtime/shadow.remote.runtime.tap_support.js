goog.provide('shadow.remote.runtime.tap_support');
shadow.remote.runtime.tap_support.tap_subscribe = (function shadow$remote$runtime$tap_support$tap_subscribe(p__20436,p__20437){
var map__20438 = p__20436;
var map__20438__$1 = cljs.core.__destructure_map(map__20438);
var svc = map__20438__$1;
var subs_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20438__$1,new cljs.core.Keyword(null,"subs-ref","subs-ref",-1355989911));
var obj_support = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20438__$1,new cljs.core.Keyword(null,"obj-support","obj-support",1522559229));
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20438__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
var map__20439 = p__20437;
var map__20439__$1 = cljs.core.__destructure_map(map__20439);
var msg = map__20439__$1;
var from = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20439__$1,new cljs.core.Keyword(null,"from","from",1815293044));
var summary = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20439__$1,new cljs.core.Keyword(null,"summary","summary",380847952));
var history__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20439__$1,new cljs.core.Keyword(null,"history","history",-247395220));
var num = cljs.core.get.cljs$core$IFn$_invoke$arity$3(map__20439__$1,new cljs.core.Keyword(null,"num","num",1985240673),(10));
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(subs_ref,cljs.core.assoc,from,msg);

if(cljs.core.truth_(history__$1)){
return shadow.remote.runtime.shared.reply(runtime,msg,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"tap-subscribed","tap-subscribed",-1882247432),new cljs.core.Keyword(null,"history","history",-247395220),cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentVector.EMPTY,cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (oid){
return new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"oid","oid",-768692334),oid,new cljs.core.Keyword(null,"summary","summary",380847952),shadow.remote.runtime.obj_support.obj_describe_STAR_(obj_support,oid)], null);
}),shadow.remote.runtime.obj_support.get_tap_history(obj_support,num)))], null));
} else {
return null;
}
});
shadow.remote.runtime.tap_support.tap_unsubscribe = (function shadow$remote$runtime$tap_support$tap_unsubscribe(p__20447,p__20448){
var map__20449 = p__20447;
var map__20449__$1 = cljs.core.__destructure_map(map__20449);
var subs_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20449__$1,new cljs.core.Keyword(null,"subs-ref","subs-ref",-1355989911));
var map__20450 = p__20448;
var map__20450__$1 = cljs.core.__destructure_map(map__20450);
var from = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20450__$1,new cljs.core.Keyword(null,"from","from",1815293044));
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(subs_ref,cljs.core.dissoc,from);
});
shadow.remote.runtime.tap_support.request_tap_history = (function shadow$remote$runtime$tap_support$request_tap_history(p__20455,p__20456){
var map__20459 = p__20455;
var map__20459__$1 = cljs.core.__destructure_map(map__20459);
var obj_support = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20459__$1,new cljs.core.Keyword(null,"obj-support","obj-support",1522559229));
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20459__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
var map__20460 = p__20456;
var map__20460__$1 = cljs.core.__destructure_map(map__20460);
var msg = map__20460__$1;
var num = cljs.core.get.cljs$core$IFn$_invoke$arity$3(map__20460__$1,new cljs.core.Keyword(null,"num","num",1985240673),(10));
var tap_ids = shadow.remote.runtime.obj_support.get_tap_history(obj_support,num);
return shadow.remote.runtime.shared.reply(runtime,msg,new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"tap-history","tap-history",-282803347),new cljs.core.Keyword(null,"oids","oids",-1580877688),tap_ids], null));
});
shadow.remote.runtime.tap_support.tool_disconnect = (function shadow$remote$runtime$tap_support$tool_disconnect(p__20465,tid){
var map__20466 = p__20465;
var map__20466__$1 = cljs.core.__destructure_map(map__20466);
var svc = map__20466__$1;
var subs_ref = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20466__$1,new cljs.core.Keyword(null,"subs-ref","subs-ref",-1355989911));
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(subs_ref,cljs.core.dissoc,tid);
});
shadow.remote.runtime.tap_support.start = (function shadow$remote$runtime$tap_support$start(runtime,obj_support){
var subs_ref = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
var tap_fn = (function shadow$remote$runtime$tap_support$start_$_runtime_tap(obj){
if((!((obj == null)))){
var oid = shadow.remote.runtime.obj_support.register(obj_support,obj,new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"from","from",1815293044),new cljs.core.Keyword(null,"tap","tap",-1086702463)], null));
var seq__20472 = cljs.core.seq(cljs.core.deref(subs_ref));
var chunk__20473 = null;
var count__20474 = (0);
var i__20475 = (0);
while(true){
if((i__20475 < count__20474)){
var vec__20488 = chunk__20473.cljs$core$IIndexed$_nth$arity$2(null,i__20475);
var tid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20488,(0),null);
var tap_config = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20488,(1),null);
shadow.remote.runtime.api.relay_msg(runtime,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"tap","tap",-1086702463),new cljs.core.Keyword(null,"to","to",192099007),tid,new cljs.core.Keyword(null,"oid","oid",-768692334),oid], null));


var G__20509 = seq__20472;
var G__20510 = chunk__20473;
var G__20511 = count__20474;
var G__20512 = (i__20475 + (1));
seq__20472 = G__20509;
chunk__20473 = G__20510;
count__20474 = G__20511;
i__20475 = G__20512;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__20472);
if(temp__5823__auto__){
var seq__20472__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__20472__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__20472__$1);
var G__20513 = cljs.core.chunk_rest(seq__20472__$1);
var G__20514 = c__5673__auto__;
var G__20515 = cljs.core.count(c__5673__auto__);
var G__20516 = (0);
seq__20472 = G__20513;
chunk__20473 = G__20514;
count__20474 = G__20515;
i__20475 = G__20516;
continue;
} else {
var vec__20491 = cljs.core.first(seq__20472__$1);
var tid = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20491,(0),null);
var tap_config = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__20491,(1),null);
shadow.remote.runtime.api.relay_msg(runtime,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"tap","tap",-1086702463),new cljs.core.Keyword(null,"to","to",192099007),tid,new cljs.core.Keyword(null,"oid","oid",-768692334),oid], null));


var G__20517 = cljs.core.next(seq__20472__$1);
var G__20518 = null;
var G__20519 = (0);
var G__20520 = (0);
seq__20472 = G__20517;
chunk__20473 = G__20518;
count__20474 = G__20519;
i__20475 = G__20520;
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
});
var svc = new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"runtime","runtime",-1331573996),runtime,new cljs.core.Keyword(null,"obj-support","obj-support",1522559229),obj_support,new cljs.core.Keyword(null,"tap-fn","tap-fn",1573556461),tap_fn,new cljs.core.Keyword(null,"subs-ref","subs-ref",-1355989911),subs_ref], null);
shadow.remote.runtime.api.add_extension(runtime,new cljs.core.Keyword("shadow.remote.runtime.tap-support","ext","shadow.remote.runtime.tap-support/ext",1019069674),new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"ops","ops",1237330063),new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"tap-subscribe","tap-subscribe",411179050),(function (p1__20468_SHARP_){
return shadow.remote.runtime.tap_support.tap_subscribe(svc,p1__20468_SHARP_);
}),new cljs.core.Keyword(null,"tap-unsubscribe","tap-unsubscribe",1183890755),(function (p1__20469_SHARP_){
return shadow.remote.runtime.tap_support.tap_unsubscribe(svc,p1__20469_SHARP_);
}),new cljs.core.Keyword(null,"request-tap-history","request-tap-history",-670837812),(function (p1__20470_SHARP_){
return shadow.remote.runtime.tap_support.request_tap_history(svc,p1__20470_SHARP_);
})], null),new cljs.core.Keyword(null,"on-tool-disconnect","on-tool-disconnect",693464366),(function (p1__20471_SHARP_){
return shadow.remote.runtime.tap_support.tool_disconnect(svc,p1__20471_SHARP_);
})], null));

cljs.core.add_tap(tap_fn);

return svc;
});
shadow.remote.runtime.tap_support.stop = (function shadow$remote$runtime$tap_support$stop(p__20496){
var map__20497 = p__20496;
var map__20497__$1 = cljs.core.__destructure_map(map__20497);
var svc = map__20497__$1;
var tap_fn = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20497__$1,new cljs.core.Keyword(null,"tap-fn","tap-fn",1573556461));
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__20497__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
cljs.core.remove_tap(tap_fn);

return shadow.remote.runtime.api.del_extension(runtime,new cljs.core.Keyword("shadow.remote.runtime.tap-support","ext","shadow.remote.runtime.tap-support/ext",1019069674));
});

//# sourceMappingURL=shadow.remote.runtime.tap_support.js.map
