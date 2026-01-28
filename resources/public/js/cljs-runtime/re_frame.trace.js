goog.provide('re_frame.trace');
re_frame.trace.id = cljs.core.atom.cljs$core$IFn$_invoke$arity$1((0));
re_frame.trace._STAR_current_trace_STAR_ = null;
re_frame.trace.reset_tracing_BANG_ = (function re_frame$trace$reset_tracing_BANG_(){
return cljs.core.reset_BANG_(re_frame.trace.id,(0));
});
/**
 * @define {boolean}
 * @type {boolean}
 */
re_frame.trace.trace_enabled_QMARK_ = goog.define("re_frame.trace.trace_enabled_QMARK_",false);
/**
 * See https://groups.google.com/d/msg/clojurescript/jk43kmYiMhA/IHglVr_TPdgJ for more details
 */
re_frame.trace.is_trace_enabled_QMARK_ = (function re_frame$trace$is_trace_enabled_QMARK_(){
return re_frame.trace.trace_enabled_QMARK_;
});
re_frame.trace.trace_cbs = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
if((typeof re_frame !== 'undefined') && (typeof re_frame.trace !== 'undefined') && (typeof re_frame.trace.traces !== 'undefined')){
} else {
re_frame.trace.traces = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentVector.EMPTY);
}
if((typeof re_frame !== 'undefined') && (typeof re_frame.trace !== 'undefined') && (typeof re_frame.trace.next_delivery !== 'undefined')){
} else {
re_frame.trace.next_delivery = cljs.core.atom.cljs$core$IFn$_invoke$arity$1((0));
}
/**
 * Registers a tracing callback function which will receive a collection of one or more traces.
 *   Will replace an existing callback function if it shares the same key.
 */
re_frame.trace.register_trace_cb = (function re_frame$trace$register_trace_cb(key,f){
if(re_frame.trace.trace_enabled_QMARK_){
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(re_frame.trace.trace_cbs,cljs.core.assoc,key,f);
} else {
return re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"warn","warn",-436710552),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Tracing is not enabled. Please set {\"re_frame.trace.trace_enabled_QMARK_\" true} in :closure-defines. See: https://github.com/day8/re-frame-10x#installation."], 0));
}
});
re_frame.trace.remove_trace_cb = (function re_frame$trace$remove_trace_cb(key){
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(re_frame.trace.trace_cbs,cljs.core.dissoc,key);

return null;
});
re_frame.trace.next_id = (function re_frame$trace$next_id(){
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(re_frame.trace.id,cljs.core.inc);
});
re_frame.trace.start_trace = (function re_frame$trace$start_trace(p__22719){
var map__22720 = p__22719;
var map__22720__$1 = cljs.core.__destructure_map(map__22720);
var operation = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__22720__$1,new cljs.core.Keyword(null,"operation","operation",-1267664310));
var op_type = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__22720__$1,new cljs.core.Keyword(null,"op-type","op-type",-1636141668));
var tags = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__22720__$1,new cljs.core.Keyword(null,"tags","tags",1771418977));
var child_of = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__22720__$1,new cljs.core.Keyword(null,"child-of","child-of",-903376662));
return new cljs.core.PersistentArrayMap(null, 6, [new cljs.core.Keyword(null,"id","id",-1388402092),re_frame.trace.next_id(),new cljs.core.Keyword(null,"operation","operation",-1267664310),operation,new cljs.core.Keyword(null,"op-type","op-type",-1636141668),op_type,new cljs.core.Keyword(null,"tags","tags",1771418977),tags,new cljs.core.Keyword(null,"child-of","child-of",-903376662),(function (){var or__5142__auto__ = child_of;
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return new cljs.core.Keyword(null,"id","id",-1388402092).cljs$core$IFn$_invoke$arity$1(re_frame.trace._STAR_current_trace_STAR_);
}
})(),new cljs.core.Keyword(null,"start","start",-355208981),re_frame.interop.now()], null);
});
re_frame.trace.debounce_time = (50);
re_frame.trace.debounce = (function re_frame$trace$debounce(f,interval){
return goog.functions.debounce(f,interval);
});
re_frame.trace.schedule_debounce = re_frame.trace.debounce((function re_frame$trace$tracing_cb_debounced(){
var seq__22724_22764 = cljs.core.seq(cljs.core.deref(re_frame.trace.trace_cbs));
var chunk__22728_22765 = null;
var count__22729_22766 = (0);
var i__22730_22767 = (0);
while(true){
if((i__22730_22767 < count__22729_22766)){
var vec__22743_22768 = chunk__22728_22765.cljs$core$IIndexed$_nth$arity$2(null,i__22730_22767);
var k_22769 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__22743_22768,(0),null);
var cb_22770 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__22743_22768,(1),null);
try{var G__22747_22771 = cljs.core.deref(re_frame.trace.traces);
(cb_22770.cljs$core$IFn$_invoke$arity$1 ? cb_22770.cljs$core$IFn$_invoke$arity$1(G__22747_22771) : cb_22770.call(null,G__22747_22771));
}catch (e22746){var e_22772 = e22746;
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Error thrown from trace cb",k_22769,"while storing",cljs.core.deref(re_frame.trace.traces),e_22772], 0));
}

var G__22773 = seq__22724_22764;
var G__22774 = chunk__22728_22765;
var G__22775 = count__22729_22766;
var G__22776 = (i__22730_22767 + (1));
seq__22724_22764 = G__22773;
chunk__22728_22765 = G__22774;
count__22729_22766 = G__22775;
i__22730_22767 = G__22776;
continue;
} else {
var temp__5823__auto___22777 = cljs.core.seq(seq__22724_22764);
if(temp__5823__auto___22777){
var seq__22724_22781__$1 = temp__5823__auto___22777;
if(cljs.core.chunked_seq_QMARK_(seq__22724_22781__$1)){
var c__5673__auto___22783 = cljs.core.chunk_first(seq__22724_22781__$1);
var G__22784 = cljs.core.chunk_rest(seq__22724_22781__$1);
var G__22785 = c__5673__auto___22783;
var G__22786 = cljs.core.count(c__5673__auto___22783);
var G__22787 = (0);
seq__22724_22764 = G__22784;
chunk__22728_22765 = G__22785;
count__22729_22766 = G__22786;
i__22730_22767 = G__22787;
continue;
} else {
var vec__22752_22788 = cljs.core.first(seq__22724_22781__$1);
var k_22789 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__22752_22788,(0),null);
var cb_22790 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__22752_22788,(1),null);
try{var G__22760_22791 = cljs.core.deref(re_frame.trace.traces);
(cb_22790.cljs$core$IFn$_invoke$arity$1 ? cb_22790.cljs$core$IFn$_invoke$arity$1(G__22760_22791) : cb_22790.call(null,G__22760_22791));
}catch (e22756){var e_22792 = e22756;
re_frame.loggers.console.cljs$core$IFn$_invoke$arity$variadic(new cljs.core.Keyword(null,"error","error",-978969032),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["Error thrown from trace cb",k_22789,"while storing",cljs.core.deref(re_frame.trace.traces),e_22792], 0));
}

var G__22793 = cljs.core.next(seq__22724_22781__$1);
var G__22794 = null;
var G__22795 = (0);
var G__22796 = (0);
seq__22724_22764 = G__22793;
chunk__22728_22765 = G__22794;
count__22729_22766 = G__22795;
i__22730_22767 = G__22796;
continue;
}
} else {
}
}
break;
}

return cljs.core.reset_BANG_(re_frame.trace.traces,cljs.core.PersistentVector.EMPTY);
}),re_frame.trace.debounce_time);
re_frame.trace.run_tracing_callbacks_BANG_ = (function re_frame$trace$run_tracing_callbacks_BANG_(now){
if(((cljs.core.deref(re_frame.trace.next_delivery) - (25)) < now)){
(re_frame.trace.schedule_debounce.cljs$core$IFn$_invoke$arity$0 ? re_frame.trace.schedule_debounce.cljs$core$IFn$_invoke$arity$0() : re_frame.trace.schedule_debounce.call(null));

return cljs.core.reset_BANG_(re_frame.trace.next_delivery,(now + re_frame.trace.debounce_time));
} else {
return null;
}
});

//# sourceMappingURL=re_frame.trace.js.map
