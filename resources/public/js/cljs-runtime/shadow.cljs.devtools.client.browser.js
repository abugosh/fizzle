goog.provide('shadow.cljs.devtools.client.browser');
shadow.cljs.devtools.client.browser.devtools_msg = (function shadow$cljs$devtools$client$browser$devtools_msg(var_args){
var args__5882__auto__ = [];
var len__5876__auto___21600 = arguments.length;
var i__5877__auto___21601 = (0);
while(true){
if((i__5877__auto___21601 < len__5876__auto___21600)){
args__5882__auto__.push((arguments[i__5877__auto___21601]));

var G__21602 = (i__5877__auto___21601 + (1));
i__5877__auto___21601 = G__21602;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((1) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((1)),(0),null)):null);
return shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),argseq__5883__auto__);
});

(shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic = (function (msg,args){
if(shadow.cljs.devtools.client.env.log){
if(cljs.core.seq(shadow.cljs.devtools.client.env.log_style)){
return console.log.apply(console,cljs.core.into_array.cljs$core$IFn$_invoke$arity$1(cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(""+"%cshadow-cljs: "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(msg)),shadow.cljs.devtools.client.env.log_style], null),args)));
} else {
return console.log.apply(console,cljs.core.into_array.cljs$core$IFn$_invoke$arity$1(cljs.core.into.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [(""+"shadow-cljs: "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(msg))], null),args)));
}
} else {
return null;
}
}));

(shadow.cljs.devtools.client.browser.devtools_msg.cljs$lang$maxFixedArity = (1));

/** @this {Function} */
(shadow.cljs.devtools.client.browser.devtools_msg.cljs$lang$applyTo = (function (seq20948){
var G__20949 = cljs.core.first(seq20948);
var seq20948__$1 = cljs.core.next(seq20948);
var self__5861__auto__ = this;
return self__5861__auto__.cljs$core$IFn$_invoke$arity$variadic(G__20949,seq20948__$1);
}));

shadow.cljs.devtools.client.browser.script_eval = (function shadow$cljs$devtools$client$browser$script_eval(code){
return goog.globalEval(code);
});
shadow.cljs.devtools.client.browser.do_js_load = (function shadow$cljs$devtools$client$browser$do_js_load(sources){
var seq__20980 = cljs.core.seq(sources);
var chunk__20981 = null;
var count__20982 = (0);
var i__20983 = (0);
while(true){
if((i__20983 < count__20982)){
var map__21007 = chunk__20981.cljs$core$IIndexed$_nth$arity$2(null,i__20983);
var map__21007__$1 = cljs.core.__destructure_map(map__21007);
var src = map__21007__$1;
var resource_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21007__$1,new cljs.core.Keyword(null,"resource-id","resource-id",-1308422582));
var output_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21007__$1,new cljs.core.Keyword(null,"output-name","output-name",-1769107767));
var resource_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21007__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
var js = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21007__$1,new cljs.core.Keyword(null,"js","js",1768080579));
$CLJS.SHADOW_ENV.setLoaded(output_name);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load JS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([resource_name], 0));

shadow.cljs.devtools.client.env.before_load_src(src);

try{shadow.cljs.devtools.client.browser.script_eval((""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(js)+"\n//# sourceURL="+cljs.core.str.cljs$core$IFn$_invoke$arity$1($CLJS.SHADOW_ENV.scriptBase)+cljs.core.str.cljs$core$IFn$_invoke$arity$1(output_name)));
}catch (e21009){var e_21607 = e21009;
if(shadow.cljs.devtools.client.env.log){
console.error((""+"Failed to load "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name)),e_21607);
} else {
}

throw (new Error((""+"Failed to load "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name)+": "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(e_21607.message))));
}

var G__21608 = seq__20980;
var G__21609 = chunk__20981;
var G__21610 = count__20982;
var G__21611 = (i__20983 + (1));
seq__20980 = G__21608;
chunk__20981 = G__21609;
count__20982 = G__21610;
i__20983 = G__21611;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__20980);
if(temp__5823__auto__){
var seq__20980__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__20980__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__20980__$1);
var G__21612 = cljs.core.chunk_rest(seq__20980__$1);
var G__21613 = c__5673__auto__;
var G__21614 = cljs.core.count(c__5673__auto__);
var G__21615 = (0);
seq__20980 = G__21612;
chunk__20981 = G__21613;
count__20982 = G__21614;
i__20983 = G__21615;
continue;
} else {
var map__21015 = cljs.core.first(seq__20980__$1);
var map__21015__$1 = cljs.core.__destructure_map(map__21015);
var src = map__21015__$1;
var resource_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21015__$1,new cljs.core.Keyword(null,"resource-id","resource-id",-1308422582));
var output_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21015__$1,new cljs.core.Keyword(null,"output-name","output-name",-1769107767));
var resource_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21015__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
var js = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21015__$1,new cljs.core.Keyword(null,"js","js",1768080579));
$CLJS.SHADOW_ENV.setLoaded(output_name);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load JS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([resource_name], 0));

shadow.cljs.devtools.client.env.before_load_src(src);

try{shadow.cljs.devtools.client.browser.script_eval((""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(js)+"\n//# sourceURL="+cljs.core.str.cljs$core$IFn$_invoke$arity$1($CLJS.SHADOW_ENV.scriptBase)+cljs.core.str.cljs$core$IFn$_invoke$arity$1(output_name)));
}catch (e21021){var e_21616 = e21021;
if(shadow.cljs.devtools.client.env.log){
console.error((""+"Failed to load "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name)),e_21616);
} else {
}

throw (new Error((""+"Failed to load "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name)+": "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(e_21616.message))));
}

var G__21617 = cljs.core.next(seq__20980__$1);
var G__21618 = null;
var G__21619 = (0);
var G__21620 = (0);
seq__20980 = G__21617;
chunk__20981 = G__21618;
count__20982 = G__21619;
i__20983 = G__21620;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.cljs.devtools.client.browser.do_js_reload = (function shadow$cljs$devtools$client$browser$do_js_reload(msg,sources,complete_fn,failure_fn){
return shadow.cljs.devtools.client.env.do_js_reload.cljs$core$IFn$_invoke$arity$4(cljs.core.assoc.cljs$core$IFn$_invoke$arity$variadic(msg,new cljs.core.Keyword(null,"log-missing-fn","log-missing-fn",732676765),(function (fn_sym){
return null;
}),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"log-call-async","log-call-async",183826192),(function (fn_sym){
return shadow.cljs.devtools.client.browser.devtools_msg((""+"call async "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym)));
}),new cljs.core.Keyword(null,"log-call","log-call",412404391),(function (fn_sym){
return shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym)));
})], 0)),(function (next){
shadow.cljs.devtools.client.browser.do_js_load(sources);

return (next.cljs$core$IFn$_invoke$arity$0 ? next.cljs$core$IFn$_invoke$arity$0() : next.call(null));
}),complete_fn,failure_fn);
});
/**
 * when (require '["some-str" :as x]) is done at the REPL we need to manually call the shadow.js.require for it
 * since the file only adds the shadow$provide. only need to do this for shadow-js.
 */
shadow.cljs.devtools.client.browser.do_js_requires = (function shadow$cljs$devtools$client$browser$do_js_requires(js_requires){
var seq__21057 = cljs.core.seq(js_requires);
var chunk__21058 = null;
var count__21059 = (0);
var i__21060 = (0);
while(true){
if((i__21060 < count__21059)){
var js_ns = chunk__21058.cljs$core$IIndexed$_nth$arity$2(null,i__21060);
var require_str_21622 = (""+"var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(js_ns)+" = shadow.js.require(\""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(js_ns)+"\");");
shadow.cljs.devtools.client.browser.script_eval(require_str_21622);


var G__21623 = seq__21057;
var G__21624 = chunk__21058;
var G__21625 = count__21059;
var G__21626 = (i__21060 + (1));
seq__21057 = G__21623;
chunk__21058 = G__21624;
count__21059 = G__21625;
i__21060 = G__21626;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__21057);
if(temp__5823__auto__){
var seq__21057__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__21057__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__21057__$1);
var G__21627 = cljs.core.chunk_rest(seq__21057__$1);
var G__21628 = c__5673__auto__;
var G__21629 = cljs.core.count(c__5673__auto__);
var G__21630 = (0);
seq__21057 = G__21627;
chunk__21058 = G__21628;
count__21059 = G__21629;
i__21060 = G__21630;
continue;
} else {
var js_ns = cljs.core.first(seq__21057__$1);
var require_str_21631 = (""+"var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(js_ns)+" = shadow.js.require(\""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(js_ns)+"\");");
shadow.cljs.devtools.client.browser.script_eval(require_str_21631);


var G__21633 = cljs.core.next(seq__21057__$1);
var G__21634 = null;
var G__21635 = (0);
var G__21636 = (0);
seq__21057 = G__21633;
chunk__21058 = G__21634;
count__21059 = G__21635;
i__21060 = G__21636;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.cljs.devtools.client.browser.handle_build_complete = (function shadow$cljs$devtools$client$browser$handle_build_complete(runtime,p__21086){
var map__21087 = p__21086;
var map__21087__$1 = cljs.core.__destructure_map(map__21087);
var msg = map__21087__$1;
var info = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21087__$1,new cljs.core.Keyword(null,"info","info",-317069002));
var reload_info = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21087__$1,new cljs.core.Keyword(null,"reload-info","reload-info",1648088086));
var warnings = cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentVector.EMPTY,cljs.core.distinct.cljs$core$IFn$_invoke$arity$1((function (){var iter__5628__auto__ = (function shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__21092(s__21093){
return (new cljs.core.LazySeq(null,(function (){
var s__21093__$1 = s__21093;
while(true){
var temp__5823__auto__ = cljs.core.seq(s__21093__$1);
if(temp__5823__auto__){
var xs__6383__auto__ = temp__5823__auto__;
var map__21106 = cljs.core.first(xs__6383__auto__);
var map__21106__$1 = cljs.core.__destructure_map(map__21106);
var src = map__21106__$1;
var resource_name = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21106__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
var warnings = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21106__$1,new cljs.core.Keyword(null,"warnings","warnings",-735437651));
if(cljs.core.not(new cljs.core.Keyword(null,"from-jar","from-jar",1050932827).cljs$core$IFn$_invoke$arity$1(src))){
var iterys__5624__auto__ = ((function (s__21093__$1,map__21106,map__21106__$1,src,resource_name,warnings,xs__6383__auto__,temp__5823__auto__,map__21087,map__21087__$1,msg,info,reload_info){
return (function shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__21092_$_iter__21094(s__21095){
return (new cljs.core.LazySeq(null,((function (s__21093__$1,map__21106,map__21106__$1,src,resource_name,warnings,xs__6383__auto__,temp__5823__auto__,map__21087,map__21087__$1,msg,info,reload_info){
return (function (){
var s__21095__$1 = s__21095;
while(true){
var temp__5823__auto____$1 = cljs.core.seq(s__21095__$1);
if(temp__5823__auto____$1){
var s__21095__$2 = temp__5823__auto____$1;
if(cljs.core.chunked_seq_QMARK_(s__21095__$2)){
var c__5626__auto__ = cljs.core.chunk_first(s__21095__$2);
var size__5627__auto__ = cljs.core.count(c__5626__auto__);
var b__21097 = cljs.core.chunk_buffer(size__5627__auto__);
if((function (){var i__21096 = (0);
while(true){
if((i__21096 < size__5627__auto__)){
var warning = cljs.core._nth(c__5626__auto__,i__21096);
cljs.core.chunk_append(b__21097,cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(warning,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100),resource_name));

var G__21641 = (i__21096 + (1));
i__21096 = G__21641;
continue;
} else {
return true;
}
break;
}
})()){
return cljs.core.chunk_cons(cljs.core.chunk(b__21097),shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__21092_$_iter__21094(cljs.core.chunk_rest(s__21095__$2)));
} else {
return cljs.core.chunk_cons(cljs.core.chunk(b__21097),null);
}
} else {
var warning = cljs.core.first(s__21095__$2);
return cljs.core.cons(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(warning,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100),resource_name),shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__21092_$_iter__21094(cljs.core.rest(s__21095__$2)));
}
} else {
return null;
}
break;
}
});})(s__21093__$1,map__21106,map__21106__$1,src,resource_name,warnings,xs__6383__auto__,temp__5823__auto__,map__21087,map__21087__$1,msg,info,reload_info))
,null,null));
});})(s__21093__$1,map__21106,map__21106__$1,src,resource_name,warnings,xs__6383__auto__,temp__5823__auto__,map__21087,map__21087__$1,msg,info,reload_info))
;
var fs__5625__auto__ = cljs.core.seq(iterys__5624__auto__(warnings));
if(fs__5625__auto__){
return cljs.core.concat.cljs$core$IFn$_invoke$arity$2(fs__5625__auto__,shadow$cljs$devtools$client$browser$handle_build_complete_$_iter__21092(cljs.core.rest(s__21093__$1)));
} else {
var G__21660 = cljs.core.rest(s__21093__$1);
s__21093__$1 = G__21660;
continue;
}
} else {
var G__21662 = cljs.core.rest(s__21093__$1);
s__21093__$1 = G__21662;
continue;
}
} else {
return null;
}
break;
}
}),null,null));
});
return iter__5628__auto__(new cljs.core.Keyword(null,"sources","sources",-321166424).cljs$core$IFn$_invoke$arity$1(info));
})()));
if(shadow.cljs.devtools.client.env.log){
var seq__21130_21667 = cljs.core.seq(warnings);
var chunk__21131_21668 = null;
var count__21132_21669 = (0);
var i__21133_21670 = (0);
while(true){
if((i__21133_21670 < count__21132_21669)){
var map__21153_21675 = chunk__21131_21668.cljs$core$IIndexed$_nth$arity$2(null,i__21133_21670);
var map__21153_21676__$1 = cljs.core.__destructure_map(map__21153_21675);
var w_21677 = map__21153_21676__$1;
var msg_21678__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21153_21676__$1,new cljs.core.Keyword(null,"msg","msg",-1386103444));
var line_21679 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21153_21676__$1,new cljs.core.Keyword(null,"line","line",212345235));
var column_21680 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21153_21676__$1,new cljs.core.Keyword(null,"column","column",2078222095));
var resource_name_21681 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21153_21676__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
console.warn((""+"BUILD-WARNING in "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name_21681)+" at ["+cljs.core.str.cljs$core$IFn$_invoke$arity$1(line_21679)+":"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(column_21680)+"]\n\t"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(msg_21678__$1)));


var G__21690 = seq__21130_21667;
var G__21691 = chunk__21131_21668;
var G__21692 = count__21132_21669;
var G__21693 = (i__21133_21670 + (1));
seq__21130_21667 = G__21690;
chunk__21131_21668 = G__21691;
count__21132_21669 = G__21692;
i__21133_21670 = G__21693;
continue;
} else {
var temp__5823__auto___21694 = cljs.core.seq(seq__21130_21667);
if(temp__5823__auto___21694){
var seq__21130_21696__$1 = temp__5823__auto___21694;
if(cljs.core.chunked_seq_QMARK_(seq__21130_21696__$1)){
var c__5673__auto___21697 = cljs.core.chunk_first(seq__21130_21696__$1);
var G__21698 = cljs.core.chunk_rest(seq__21130_21696__$1);
var G__21699 = c__5673__auto___21697;
var G__21700 = cljs.core.count(c__5673__auto___21697);
var G__21701 = (0);
seq__21130_21667 = G__21698;
chunk__21131_21668 = G__21699;
count__21132_21669 = G__21700;
i__21133_21670 = G__21701;
continue;
} else {
var map__21162_21702 = cljs.core.first(seq__21130_21696__$1);
var map__21162_21703__$1 = cljs.core.__destructure_map(map__21162_21702);
var w_21704 = map__21162_21703__$1;
var msg_21705__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21162_21703__$1,new cljs.core.Keyword(null,"msg","msg",-1386103444));
var line_21706 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21162_21703__$1,new cljs.core.Keyword(null,"line","line",212345235));
var column_21707 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21162_21703__$1,new cljs.core.Keyword(null,"column","column",2078222095));
var resource_name_21708 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21162_21703__$1,new cljs.core.Keyword(null,"resource-name","resource-name",2001617100));
console.warn((""+"BUILD-WARNING in "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(resource_name_21708)+" at ["+cljs.core.str.cljs$core$IFn$_invoke$arity$1(line_21706)+":"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(column_21707)+"]\n\t"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(msg_21705__$1)));


var G__21709 = cljs.core.next(seq__21130_21696__$1);
var G__21710 = null;
var G__21711 = (0);
var G__21712 = (0);
seq__21130_21667 = G__21709;
chunk__21131_21668 = G__21710;
count__21132_21669 = G__21711;
i__21133_21670 = G__21712;
continue;
}
} else {
}
}
break;
}
} else {
}

if((!(shadow.cljs.devtools.client.env.autoload))){
return shadow.cljs.devtools.client.hud.load_end_success();
} else {
if(((cljs.core.empty_QMARK_(warnings)) || (shadow.cljs.devtools.client.env.ignore_warnings))){
var sources_to_get = shadow.cljs.devtools.client.env.filter_reload_sources(info,reload_info);
if(cljs.core.not(cljs.core.seq(sources_to_get))){
return shadow.cljs.devtools.client.hud.load_end_success();
} else {
if(cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"after-load","after-load",-1278503285)], null)))){
} else {
shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("reloading code but no :after-load hooks are configured!",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["https://shadow-cljs.github.io/docs/UsersGuide.html#_lifecycle_hooks"], 0));
}

return shadow.cljs.devtools.client.shared.load_sources(runtime,sources_to_get,(function (p1__21081_SHARP_){
return shadow.cljs.devtools.client.browser.do_js_reload(msg,p1__21081_SHARP_,shadow.cljs.devtools.client.hud.load_end_success,shadow.cljs.devtools.client.hud.load_failure);
}));
}
} else {
return null;
}
}
});
shadow.cljs.devtools.client.browser.page_load_uri = (cljs.core.truth_(goog.global.document)?goog.Uri.parse(document.location.href):null);
shadow.cljs.devtools.client.browser.match_paths = (function shadow$cljs$devtools$client$browser$match_paths(old,new$){
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2("file",shadow.cljs.devtools.client.browser.page_load_uri.getScheme())){
var rel_new = cljs.core.subs.cljs$core$IFn$_invoke$arity$2(new$,(1));
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(old,rel_new)) || (clojure.string.starts_with_QMARK_(old,(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(rel_new)+"?"))))){
return rel_new;
} else {
return null;
}
} else {
var node_uri = goog.Uri.parse(old);
var node_uri_resolved = shadow.cljs.devtools.client.browser.page_load_uri.resolve(node_uri);
var node_abs = node_uri_resolved.getPath();
var and__5140__auto__ = ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$1(shadow.cljs.devtools.client.browser.page_load_uri.hasSameDomainAs(node_uri))) || (cljs.core.not(node_uri.hasDomain())));
if(and__5140__auto__){
var and__5140__auto____$1 = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(node_abs,new$);
if(and__5140__auto____$1){
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1((function (){var G__21223 = node_uri;
G__21223.setQuery(null);

G__21223.setPath(new$);

return G__21223;
})()));
} else {
return and__5140__auto____$1;
}
} else {
return and__5140__auto__;
}
}
});
shadow.cljs.devtools.client.browser.handle_asset_update = (function shadow$cljs$devtools$client$browser$handle_asset_update(p__21235){
var map__21236 = p__21235;
var map__21236__$1 = cljs.core.__destructure_map(map__21236);
var msg = map__21236__$1;
var updates = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21236__$1,new cljs.core.Keyword(null,"updates","updates",2013983452));
var reload_info = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21236__$1,new cljs.core.Keyword(null,"reload-info","reload-info",1648088086));
var seq__21241 = cljs.core.seq(updates);
var chunk__21243 = null;
var count__21244 = (0);
var i__21245 = (0);
while(true){
if((i__21245 < count__21244)){
var path = chunk__21243.cljs$core$IIndexed$_nth$arity$2(null,i__21245);
if(clojure.string.ends_with_QMARK_(path,"css")){
var seq__21418_21729 = cljs.core.seq(cljs.core.array_seq.cljs$core$IFn$_invoke$arity$1(document.querySelectorAll("link[rel=\"stylesheet\"]")));
var chunk__21422_21730 = null;
var count__21423_21731 = (0);
var i__21424_21732 = (0);
while(true){
if((i__21424_21732 < count__21423_21731)){
var node_21738 = chunk__21422_21730.cljs$core$IIndexed$_nth$arity$2(null,i__21424_21732);
if(cljs.core.not(node_21738.shadow$old)){
var path_match_21739 = shadow.cljs.devtools.client.browser.match_paths(node_21738.getAttribute("href"),path);
if(cljs.core.truth_(path_match_21739)){
var new_link_21740 = (function (){var G__21458 = node_21738.cloneNode(true);
G__21458.setAttribute("href",(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(path_match_21739)+"?r="+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.rand.cljs$core$IFn$_invoke$arity$0())));

return G__21458;
})();
(node_21738.shadow$old = true);

(new_link_21740.onload = ((function (seq__21418_21729,chunk__21422_21730,count__21423_21731,i__21424_21732,seq__21241,chunk__21243,count__21244,i__21245,new_link_21740,path_match_21739,node_21738,path,map__21236,map__21236__$1,msg,updates,reload_info){
return (function (e){
var seq__21459_21743 = cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"asset-load","asset-load",-1925902322)], null)));
var chunk__21461_21744 = null;
var count__21462_21745 = (0);
var i__21463_21746 = (0);
while(true){
if((i__21463_21746 < count__21462_21745)){
var map__21467_21747 = chunk__21461_21744.cljs$core$IIndexed$_nth$arity$2(null,i__21463_21746);
var map__21467_21748__$1 = cljs.core.__destructure_map(map__21467_21747);
var task_21749 = map__21467_21748__$1;
var fn_str_21750 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21467_21748__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_21751 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21467_21748__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_21752 = goog.getObjectByName(fn_str_21750,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_21751)));

(fn_obj_21752.cljs$core$IFn$_invoke$arity$2 ? fn_obj_21752.cljs$core$IFn$_invoke$arity$2(path,new_link_21740) : fn_obj_21752.call(null,path,new_link_21740));


var G__21753 = seq__21459_21743;
var G__21754 = chunk__21461_21744;
var G__21755 = count__21462_21745;
var G__21756 = (i__21463_21746 + (1));
seq__21459_21743 = G__21753;
chunk__21461_21744 = G__21754;
count__21462_21745 = G__21755;
i__21463_21746 = G__21756;
continue;
} else {
var temp__5823__auto___21757 = cljs.core.seq(seq__21459_21743);
if(temp__5823__auto___21757){
var seq__21459_21758__$1 = temp__5823__auto___21757;
if(cljs.core.chunked_seq_QMARK_(seq__21459_21758__$1)){
var c__5673__auto___21759 = cljs.core.chunk_first(seq__21459_21758__$1);
var G__21760 = cljs.core.chunk_rest(seq__21459_21758__$1);
var G__21761 = c__5673__auto___21759;
var G__21762 = cljs.core.count(c__5673__auto___21759);
var G__21763 = (0);
seq__21459_21743 = G__21760;
chunk__21461_21744 = G__21761;
count__21462_21745 = G__21762;
i__21463_21746 = G__21763;
continue;
} else {
var map__21470_21764 = cljs.core.first(seq__21459_21758__$1);
var map__21470_21765__$1 = cljs.core.__destructure_map(map__21470_21764);
var task_21766 = map__21470_21765__$1;
var fn_str_21767 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21470_21765__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_21768 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21470_21765__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_21770 = goog.getObjectByName(fn_str_21767,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_21768)));

(fn_obj_21770.cljs$core$IFn$_invoke$arity$2 ? fn_obj_21770.cljs$core$IFn$_invoke$arity$2(path,new_link_21740) : fn_obj_21770.call(null,path,new_link_21740));


var G__21771 = cljs.core.next(seq__21459_21758__$1);
var G__21772 = null;
var G__21773 = (0);
var G__21774 = (0);
seq__21459_21743 = G__21771;
chunk__21461_21744 = G__21772;
count__21462_21745 = G__21773;
i__21463_21746 = G__21774;
continue;
}
} else {
}
}
break;
}

return goog.dom.removeNode(node_21738);
});})(seq__21418_21729,chunk__21422_21730,count__21423_21731,i__21424_21732,seq__21241,chunk__21243,count__21244,i__21245,new_link_21740,path_match_21739,node_21738,path,map__21236,map__21236__$1,msg,updates,reload_info))
);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load CSS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([path_match_21739], 0));

goog.dom.insertSiblingAfter(new_link_21740,node_21738);


var G__21775 = seq__21418_21729;
var G__21776 = chunk__21422_21730;
var G__21777 = count__21423_21731;
var G__21778 = (i__21424_21732 + (1));
seq__21418_21729 = G__21775;
chunk__21422_21730 = G__21776;
count__21423_21731 = G__21777;
i__21424_21732 = G__21778;
continue;
} else {
var G__21779 = seq__21418_21729;
var G__21780 = chunk__21422_21730;
var G__21781 = count__21423_21731;
var G__21782 = (i__21424_21732 + (1));
seq__21418_21729 = G__21779;
chunk__21422_21730 = G__21780;
count__21423_21731 = G__21781;
i__21424_21732 = G__21782;
continue;
}
} else {
var G__21783 = seq__21418_21729;
var G__21784 = chunk__21422_21730;
var G__21785 = count__21423_21731;
var G__21786 = (i__21424_21732 + (1));
seq__21418_21729 = G__21783;
chunk__21422_21730 = G__21784;
count__21423_21731 = G__21785;
i__21424_21732 = G__21786;
continue;
}
} else {
var temp__5823__auto___21787 = cljs.core.seq(seq__21418_21729);
if(temp__5823__auto___21787){
var seq__21418_21788__$1 = temp__5823__auto___21787;
if(cljs.core.chunked_seq_QMARK_(seq__21418_21788__$1)){
var c__5673__auto___21790 = cljs.core.chunk_first(seq__21418_21788__$1);
var G__21791 = cljs.core.chunk_rest(seq__21418_21788__$1);
var G__21792 = c__5673__auto___21790;
var G__21793 = cljs.core.count(c__5673__auto___21790);
var G__21794 = (0);
seq__21418_21729 = G__21791;
chunk__21422_21730 = G__21792;
count__21423_21731 = G__21793;
i__21424_21732 = G__21794;
continue;
} else {
var node_21797 = cljs.core.first(seq__21418_21788__$1);
if(cljs.core.not(node_21797.shadow$old)){
var path_match_21799 = shadow.cljs.devtools.client.browser.match_paths(node_21797.getAttribute("href"),path);
if(cljs.core.truth_(path_match_21799)){
var new_link_21800 = (function (){var G__21475 = node_21797.cloneNode(true);
G__21475.setAttribute("href",(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(path_match_21799)+"?r="+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.rand.cljs$core$IFn$_invoke$arity$0())));

return G__21475;
})();
(node_21797.shadow$old = true);

(new_link_21800.onload = ((function (seq__21418_21729,chunk__21422_21730,count__21423_21731,i__21424_21732,seq__21241,chunk__21243,count__21244,i__21245,new_link_21800,path_match_21799,node_21797,seq__21418_21788__$1,temp__5823__auto___21787,path,map__21236,map__21236__$1,msg,updates,reload_info){
return (function (e){
var seq__21476_21801 = cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"asset-load","asset-load",-1925902322)], null)));
var chunk__21478_21802 = null;
var count__21479_21803 = (0);
var i__21480_21804 = (0);
while(true){
if((i__21480_21804 < count__21479_21803)){
var map__21488_21805 = chunk__21478_21802.cljs$core$IIndexed$_nth$arity$2(null,i__21480_21804);
var map__21488_21806__$1 = cljs.core.__destructure_map(map__21488_21805);
var task_21807 = map__21488_21806__$1;
var fn_str_21808 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21488_21806__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_21809 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21488_21806__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_21810 = goog.getObjectByName(fn_str_21808,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_21809)));

(fn_obj_21810.cljs$core$IFn$_invoke$arity$2 ? fn_obj_21810.cljs$core$IFn$_invoke$arity$2(path,new_link_21800) : fn_obj_21810.call(null,path,new_link_21800));


var G__21812 = seq__21476_21801;
var G__21813 = chunk__21478_21802;
var G__21814 = count__21479_21803;
var G__21815 = (i__21480_21804 + (1));
seq__21476_21801 = G__21812;
chunk__21478_21802 = G__21813;
count__21479_21803 = G__21814;
i__21480_21804 = G__21815;
continue;
} else {
var temp__5823__auto___21818__$1 = cljs.core.seq(seq__21476_21801);
if(temp__5823__auto___21818__$1){
var seq__21476_21820__$1 = temp__5823__auto___21818__$1;
if(cljs.core.chunked_seq_QMARK_(seq__21476_21820__$1)){
var c__5673__auto___21821 = cljs.core.chunk_first(seq__21476_21820__$1);
var G__21822 = cljs.core.chunk_rest(seq__21476_21820__$1);
var G__21823 = c__5673__auto___21821;
var G__21824 = cljs.core.count(c__5673__auto___21821);
var G__21825 = (0);
seq__21476_21801 = G__21822;
chunk__21478_21802 = G__21823;
count__21479_21803 = G__21824;
i__21480_21804 = G__21825;
continue;
} else {
var map__21489_21827 = cljs.core.first(seq__21476_21820__$1);
var map__21489_21828__$1 = cljs.core.__destructure_map(map__21489_21827);
var task_21829 = map__21489_21828__$1;
var fn_str_21830 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21489_21828__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_21831 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21489_21828__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_21834 = goog.getObjectByName(fn_str_21830,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_21831)));

(fn_obj_21834.cljs$core$IFn$_invoke$arity$2 ? fn_obj_21834.cljs$core$IFn$_invoke$arity$2(path,new_link_21800) : fn_obj_21834.call(null,path,new_link_21800));


var G__21835 = cljs.core.next(seq__21476_21820__$1);
var G__21836 = null;
var G__21837 = (0);
var G__21838 = (0);
seq__21476_21801 = G__21835;
chunk__21478_21802 = G__21836;
count__21479_21803 = G__21837;
i__21480_21804 = G__21838;
continue;
}
} else {
}
}
break;
}

return goog.dom.removeNode(node_21797);
});})(seq__21418_21729,chunk__21422_21730,count__21423_21731,i__21424_21732,seq__21241,chunk__21243,count__21244,i__21245,new_link_21800,path_match_21799,node_21797,seq__21418_21788__$1,temp__5823__auto___21787,path,map__21236,map__21236__$1,msg,updates,reload_info))
);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load CSS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([path_match_21799], 0));

goog.dom.insertSiblingAfter(new_link_21800,node_21797);


var G__21839 = cljs.core.next(seq__21418_21788__$1);
var G__21840 = null;
var G__21841 = (0);
var G__21842 = (0);
seq__21418_21729 = G__21839;
chunk__21422_21730 = G__21840;
count__21423_21731 = G__21841;
i__21424_21732 = G__21842;
continue;
} else {
var G__21843 = cljs.core.next(seq__21418_21788__$1);
var G__21844 = null;
var G__21845 = (0);
var G__21846 = (0);
seq__21418_21729 = G__21843;
chunk__21422_21730 = G__21844;
count__21423_21731 = G__21845;
i__21424_21732 = G__21846;
continue;
}
} else {
var G__21847 = cljs.core.next(seq__21418_21788__$1);
var G__21848 = null;
var G__21849 = (0);
var G__21850 = (0);
seq__21418_21729 = G__21847;
chunk__21422_21730 = G__21848;
count__21423_21731 = G__21849;
i__21424_21732 = G__21850;
continue;
}
}
} else {
}
}
break;
}


var G__21851 = seq__21241;
var G__21852 = chunk__21243;
var G__21853 = count__21244;
var G__21854 = (i__21245 + (1));
seq__21241 = G__21851;
chunk__21243 = G__21852;
count__21244 = G__21853;
i__21245 = G__21854;
continue;
} else {
var G__21855 = seq__21241;
var G__21856 = chunk__21243;
var G__21857 = count__21244;
var G__21858 = (i__21245 + (1));
seq__21241 = G__21855;
chunk__21243 = G__21856;
count__21244 = G__21857;
i__21245 = G__21858;
continue;
}
} else {
var temp__5823__auto__ = cljs.core.seq(seq__21241);
if(temp__5823__auto__){
var seq__21241__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__21241__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__21241__$1);
var G__21859 = cljs.core.chunk_rest(seq__21241__$1);
var G__21860 = c__5673__auto__;
var G__21861 = cljs.core.count(c__5673__auto__);
var G__21862 = (0);
seq__21241 = G__21859;
chunk__21243 = G__21860;
count__21244 = G__21861;
i__21245 = G__21862;
continue;
} else {
var path = cljs.core.first(seq__21241__$1);
if(clojure.string.ends_with_QMARK_(path,"css")){
var seq__21491_21863 = cljs.core.seq(cljs.core.array_seq.cljs$core$IFn$_invoke$arity$1(document.querySelectorAll("link[rel=\"stylesheet\"]")));
var chunk__21495_21864 = null;
var count__21496_21865 = (0);
var i__21497_21866 = (0);
while(true){
if((i__21497_21866 < count__21496_21865)){
var node_21867 = chunk__21495_21864.cljs$core$IIndexed$_nth$arity$2(null,i__21497_21866);
if(cljs.core.not(node_21867.shadow$old)){
var path_match_21868 = shadow.cljs.devtools.client.browser.match_paths(node_21867.getAttribute("href"),path);
if(cljs.core.truth_(path_match_21868)){
var new_link_21869 = (function (){var G__21536 = node_21867.cloneNode(true);
G__21536.setAttribute("href",(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(path_match_21868)+"?r="+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.rand.cljs$core$IFn$_invoke$arity$0())));

return G__21536;
})();
(node_21867.shadow$old = true);

(new_link_21869.onload = ((function (seq__21491_21863,chunk__21495_21864,count__21496_21865,i__21497_21866,seq__21241,chunk__21243,count__21244,i__21245,new_link_21869,path_match_21868,node_21867,path,seq__21241__$1,temp__5823__auto__,map__21236,map__21236__$1,msg,updates,reload_info){
return (function (e){
var seq__21537_21871 = cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"asset-load","asset-load",-1925902322)], null)));
var chunk__21539_21872 = null;
var count__21540_21873 = (0);
var i__21541_21874 = (0);
while(true){
if((i__21541_21874 < count__21540_21873)){
var map__21547_21875 = chunk__21539_21872.cljs$core$IIndexed$_nth$arity$2(null,i__21541_21874);
var map__21547_21876__$1 = cljs.core.__destructure_map(map__21547_21875);
var task_21877 = map__21547_21876__$1;
var fn_str_21878 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21547_21876__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_21879 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21547_21876__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_21880 = goog.getObjectByName(fn_str_21878,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_21879)));

(fn_obj_21880.cljs$core$IFn$_invoke$arity$2 ? fn_obj_21880.cljs$core$IFn$_invoke$arity$2(path,new_link_21869) : fn_obj_21880.call(null,path,new_link_21869));


var G__21881 = seq__21537_21871;
var G__21882 = chunk__21539_21872;
var G__21883 = count__21540_21873;
var G__21884 = (i__21541_21874 + (1));
seq__21537_21871 = G__21881;
chunk__21539_21872 = G__21882;
count__21540_21873 = G__21883;
i__21541_21874 = G__21884;
continue;
} else {
var temp__5823__auto___21885__$1 = cljs.core.seq(seq__21537_21871);
if(temp__5823__auto___21885__$1){
var seq__21537_21886__$1 = temp__5823__auto___21885__$1;
if(cljs.core.chunked_seq_QMARK_(seq__21537_21886__$1)){
var c__5673__auto___21887 = cljs.core.chunk_first(seq__21537_21886__$1);
var G__21888 = cljs.core.chunk_rest(seq__21537_21886__$1);
var G__21889 = c__5673__auto___21887;
var G__21890 = cljs.core.count(c__5673__auto___21887);
var G__21891 = (0);
seq__21537_21871 = G__21888;
chunk__21539_21872 = G__21889;
count__21540_21873 = G__21890;
i__21541_21874 = G__21891;
continue;
} else {
var map__21550_21892 = cljs.core.first(seq__21537_21886__$1);
var map__21550_21893__$1 = cljs.core.__destructure_map(map__21550_21892);
var task_21894 = map__21550_21893__$1;
var fn_str_21895 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21550_21893__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_21896 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21550_21893__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_21897 = goog.getObjectByName(fn_str_21895,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_21896)));

(fn_obj_21897.cljs$core$IFn$_invoke$arity$2 ? fn_obj_21897.cljs$core$IFn$_invoke$arity$2(path,new_link_21869) : fn_obj_21897.call(null,path,new_link_21869));


var G__21898 = cljs.core.next(seq__21537_21886__$1);
var G__21899 = null;
var G__21900 = (0);
var G__21901 = (0);
seq__21537_21871 = G__21898;
chunk__21539_21872 = G__21899;
count__21540_21873 = G__21900;
i__21541_21874 = G__21901;
continue;
}
} else {
}
}
break;
}

return goog.dom.removeNode(node_21867);
});})(seq__21491_21863,chunk__21495_21864,count__21496_21865,i__21497_21866,seq__21241,chunk__21243,count__21244,i__21245,new_link_21869,path_match_21868,node_21867,path,seq__21241__$1,temp__5823__auto__,map__21236,map__21236__$1,msg,updates,reload_info))
);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load CSS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([path_match_21868], 0));

goog.dom.insertSiblingAfter(new_link_21869,node_21867);


var G__21902 = seq__21491_21863;
var G__21903 = chunk__21495_21864;
var G__21904 = count__21496_21865;
var G__21905 = (i__21497_21866 + (1));
seq__21491_21863 = G__21902;
chunk__21495_21864 = G__21903;
count__21496_21865 = G__21904;
i__21497_21866 = G__21905;
continue;
} else {
var G__21906 = seq__21491_21863;
var G__21907 = chunk__21495_21864;
var G__21908 = count__21496_21865;
var G__21909 = (i__21497_21866 + (1));
seq__21491_21863 = G__21906;
chunk__21495_21864 = G__21907;
count__21496_21865 = G__21908;
i__21497_21866 = G__21909;
continue;
}
} else {
var G__21910 = seq__21491_21863;
var G__21911 = chunk__21495_21864;
var G__21912 = count__21496_21865;
var G__21913 = (i__21497_21866 + (1));
seq__21491_21863 = G__21910;
chunk__21495_21864 = G__21911;
count__21496_21865 = G__21912;
i__21497_21866 = G__21913;
continue;
}
} else {
var temp__5823__auto___21914__$1 = cljs.core.seq(seq__21491_21863);
if(temp__5823__auto___21914__$1){
var seq__21491_21915__$1 = temp__5823__auto___21914__$1;
if(cljs.core.chunked_seq_QMARK_(seq__21491_21915__$1)){
var c__5673__auto___21916 = cljs.core.chunk_first(seq__21491_21915__$1);
var G__21917 = cljs.core.chunk_rest(seq__21491_21915__$1);
var G__21918 = c__5673__auto___21916;
var G__21919 = cljs.core.count(c__5673__auto___21916);
var G__21920 = (0);
seq__21491_21863 = G__21917;
chunk__21495_21864 = G__21918;
count__21496_21865 = G__21919;
i__21497_21866 = G__21920;
continue;
} else {
var node_21921 = cljs.core.first(seq__21491_21915__$1);
if(cljs.core.not(node_21921.shadow$old)){
var path_match_21922 = shadow.cljs.devtools.client.browser.match_paths(node_21921.getAttribute("href"),path);
if(cljs.core.truth_(path_match_21922)){
var new_link_21923 = (function (){var G__21553 = node_21921.cloneNode(true);
G__21553.setAttribute("href",(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(path_match_21922)+"?r="+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.rand.cljs$core$IFn$_invoke$arity$0())));

return G__21553;
})();
(node_21921.shadow$old = true);

(new_link_21923.onload = ((function (seq__21491_21863,chunk__21495_21864,count__21496_21865,i__21497_21866,seq__21241,chunk__21243,count__21244,i__21245,new_link_21923,path_match_21922,node_21921,seq__21491_21915__$1,temp__5823__auto___21914__$1,path,seq__21241__$1,temp__5823__auto__,map__21236,map__21236__$1,msg,updates,reload_info){
return (function (e){
var seq__21554_21924 = cljs.core.seq(cljs.core.get_in.cljs$core$IFn$_invoke$arity$2(msg,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"reload-info","reload-info",1648088086),new cljs.core.Keyword(null,"asset-load","asset-load",-1925902322)], null)));
var chunk__21556_21925 = null;
var count__21557_21926 = (0);
var i__21558_21927 = (0);
while(true){
if((i__21558_21927 < count__21557_21926)){
var map__21562_21928 = chunk__21556_21925.cljs$core$IIndexed$_nth$arity$2(null,i__21558_21927);
var map__21562_21929__$1 = cljs.core.__destructure_map(map__21562_21928);
var task_21930 = map__21562_21929__$1;
var fn_str_21931 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21562_21929__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_21932 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21562_21929__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_21933 = goog.getObjectByName(fn_str_21931,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_21932)));

(fn_obj_21933.cljs$core$IFn$_invoke$arity$2 ? fn_obj_21933.cljs$core$IFn$_invoke$arity$2(path,new_link_21923) : fn_obj_21933.call(null,path,new_link_21923));


var G__21934 = seq__21554_21924;
var G__21935 = chunk__21556_21925;
var G__21936 = count__21557_21926;
var G__21937 = (i__21558_21927 + (1));
seq__21554_21924 = G__21934;
chunk__21556_21925 = G__21935;
count__21557_21926 = G__21936;
i__21558_21927 = G__21937;
continue;
} else {
var temp__5823__auto___21938__$2 = cljs.core.seq(seq__21554_21924);
if(temp__5823__auto___21938__$2){
var seq__21554_21939__$1 = temp__5823__auto___21938__$2;
if(cljs.core.chunked_seq_QMARK_(seq__21554_21939__$1)){
var c__5673__auto___21940 = cljs.core.chunk_first(seq__21554_21939__$1);
var G__21941 = cljs.core.chunk_rest(seq__21554_21939__$1);
var G__21942 = c__5673__auto___21940;
var G__21943 = cljs.core.count(c__5673__auto___21940);
var G__21944 = (0);
seq__21554_21924 = G__21941;
chunk__21556_21925 = G__21942;
count__21557_21926 = G__21943;
i__21558_21927 = G__21944;
continue;
} else {
var map__21563_21945 = cljs.core.first(seq__21554_21939__$1);
var map__21563_21946__$1 = cljs.core.__destructure_map(map__21563_21945);
var task_21947 = map__21563_21946__$1;
var fn_str_21948 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21563_21946__$1,new cljs.core.Keyword(null,"fn-str","fn-str",-1348506402));
var fn_sym_21949 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21563_21946__$1,new cljs.core.Keyword(null,"fn-sym","fn-sym",1423988510));
var fn_obj_21950 = goog.getObjectByName(fn_str_21948,$CLJS);
shadow.cljs.devtools.client.browser.devtools_msg((""+"call "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(fn_sym_21949)));

(fn_obj_21950.cljs$core$IFn$_invoke$arity$2 ? fn_obj_21950.cljs$core$IFn$_invoke$arity$2(path,new_link_21923) : fn_obj_21950.call(null,path,new_link_21923));


var G__21951 = cljs.core.next(seq__21554_21939__$1);
var G__21952 = null;
var G__21953 = (0);
var G__21954 = (0);
seq__21554_21924 = G__21951;
chunk__21556_21925 = G__21952;
count__21557_21926 = G__21953;
i__21558_21927 = G__21954;
continue;
}
} else {
}
}
break;
}

return goog.dom.removeNode(node_21921);
});})(seq__21491_21863,chunk__21495_21864,count__21496_21865,i__21497_21866,seq__21241,chunk__21243,count__21244,i__21245,new_link_21923,path_match_21922,node_21921,seq__21491_21915__$1,temp__5823__auto___21914__$1,path,seq__21241__$1,temp__5823__auto__,map__21236,map__21236__$1,msg,updates,reload_info))
);

shadow.cljs.devtools.client.browser.devtools_msg.cljs$core$IFn$_invoke$arity$variadic("load CSS",cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([path_match_21922], 0));

goog.dom.insertSiblingAfter(new_link_21923,node_21921);


var G__21955 = cljs.core.next(seq__21491_21915__$1);
var G__21956 = null;
var G__21957 = (0);
var G__21958 = (0);
seq__21491_21863 = G__21955;
chunk__21495_21864 = G__21956;
count__21496_21865 = G__21957;
i__21497_21866 = G__21958;
continue;
} else {
var G__21959 = cljs.core.next(seq__21491_21915__$1);
var G__21960 = null;
var G__21961 = (0);
var G__21962 = (0);
seq__21491_21863 = G__21959;
chunk__21495_21864 = G__21960;
count__21496_21865 = G__21961;
i__21497_21866 = G__21962;
continue;
}
} else {
var G__21963 = cljs.core.next(seq__21491_21915__$1);
var G__21964 = null;
var G__21965 = (0);
var G__21966 = (0);
seq__21491_21863 = G__21963;
chunk__21495_21864 = G__21964;
count__21496_21865 = G__21965;
i__21497_21866 = G__21966;
continue;
}
}
} else {
}
}
break;
}


var G__21967 = cljs.core.next(seq__21241__$1);
var G__21968 = null;
var G__21969 = (0);
var G__21970 = (0);
seq__21241 = G__21967;
chunk__21243 = G__21968;
count__21244 = G__21969;
i__21245 = G__21970;
continue;
} else {
var G__21971 = cljs.core.next(seq__21241__$1);
var G__21972 = null;
var G__21973 = (0);
var G__21974 = (0);
seq__21241 = G__21971;
chunk__21243 = G__21972;
count__21244 = G__21973;
i__21245 = G__21974;
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
shadow.cljs.devtools.client.browser.global_eval = (function shadow$cljs$devtools$client$browser$global_eval(js){
if(cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2("undefined",typeof(module))){
return eval(js);
} else {
return (0,eval)(js);;
}
});
shadow.cljs.devtools.client.browser.runtime_info = (((typeof SHADOW_CONFIG !== 'undefined'))?shadow.json.to_clj.cljs$core$IFn$_invoke$arity$1(SHADOW_CONFIG):null);
shadow.cljs.devtools.client.browser.client_info = cljs.core.merge.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([shadow.cljs.devtools.client.browser.runtime_info,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"host","host",-1558485167),(cljs.core.truth_(goog.global.document)?new cljs.core.Keyword(null,"browser","browser",828191719):new cljs.core.Keyword(null,"browser-worker","browser-worker",1638998282)),new cljs.core.Keyword(null,"user-agent","user-agent",1220426212),(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1((cljs.core.truth_(goog.userAgent.OPERA)?"Opera":(cljs.core.truth_(goog.userAgent.product.CHROME)?"Chrome":(cljs.core.truth_(goog.userAgent.IE)?"MSIE":(cljs.core.truth_(goog.userAgent.EDGE)?"Edge":(cljs.core.truth_(goog.userAgent.GECKO)?"Firefox":(cljs.core.truth_(goog.userAgent.SAFARI)?"Safari":(cljs.core.truth_(goog.userAgent.WEBKIT)?"Webkit":null))))))))+" "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(goog.userAgent.VERSION)+" ["+cljs.core.str.cljs$core$IFn$_invoke$arity$1(goog.userAgent.PLATFORM)+"]"),new cljs.core.Keyword(null,"dom","dom",-1236537922),(!((goog.global.document == null)))], null)], 0));
if((typeof shadow !== 'undefined') && (typeof shadow.cljs !== 'undefined') && (typeof shadow.cljs.devtools !== 'undefined') && (typeof shadow.cljs.devtools.client !== 'undefined') && (typeof shadow.cljs.devtools.client.browser !== 'undefined') && (typeof shadow.cljs.devtools.client.browser.ws_was_welcome_ref !== 'undefined')){
} else {
shadow.cljs.devtools.client.browser.ws_was_welcome_ref = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(false);
}
if(((shadow.cljs.devtools.client.env.enabled) && ((shadow.cljs.devtools.client.env.worker_client_id > (0))))){
(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$remote$runtime$api$IEvalJS$ = cljs.core.PROTOCOL_SENTINEL);

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$remote$runtime$api$IEvalJS$_js_eval$arity$4 = (function (this$,code,success,fail){
var this$__$1 = this;
try{var G__21565 = shadow.cljs.devtools.client.browser.global_eval(code);
return (success.cljs$core$IFn$_invoke$arity$1 ? success.cljs$core$IFn$_invoke$arity$1(G__21565) : success.call(null,G__21565));
}catch (e21564){var e = e21564;
return (fail.cljs$core$IFn$_invoke$arity$1 ? fail.cljs$core$IFn$_invoke$arity$1(e) : fail.call(null,e));
}}));

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$cljs$devtools$client$shared$IHostSpecific$ = cljs.core.PROTOCOL_SENTINEL);

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$cljs$devtools$client$shared$IHostSpecific$do_invoke$arity$5 = (function (this$,ns,p__21566,success,fail){
var map__21568 = p__21566;
var map__21568__$1 = cljs.core.__destructure_map(map__21568);
var js = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21568__$1,new cljs.core.Keyword(null,"js","js",1768080579));
var this$__$1 = this;
try{var G__21570 = shadow.cljs.devtools.client.browser.global_eval(js);
return (success.cljs$core$IFn$_invoke$arity$1 ? success.cljs$core$IFn$_invoke$arity$1(G__21570) : success.call(null,G__21570));
}catch (e21569){var e = e21569;
return (fail.cljs$core$IFn$_invoke$arity$1 ? fail.cljs$core$IFn$_invoke$arity$1(e) : fail.call(null,e));
}}));

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$cljs$devtools$client$shared$IHostSpecific$do_repl_init$arity$4 = (function (runtime,p__21571,done,error){
var map__21572 = p__21571;
var map__21572__$1 = cljs.core.__destructure_map(map__21572);
var repl_sources = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21572__$1,new cljs.core.Keyword(null,"repl-sources","repl-sources",723867535));
var runtime__$1 = this;
return shadow.cljs.devtools.client.shared.load_sources(runtime__$1,cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentVector.EMPTY,cljs.core.remove.cljs$core$IFn$_invoke$arity$2(shadow.cljs.devtools.client.env.src_is_loaded_QMARK_,repl_sources)),(function (sources){
shadow.cljs.devtools.client.browser.do_js_load(sources);

return (done.cljs$core$IFn$_invoke$arity$0 ? done.cljs$core$IFn$_invoke$arity$0() : done.call(null));
}));
}));

(shadow.cljs.devtools.client.shared.Runtime.prototype.shadow$cljs$devtools$client$shared$IHostSpecific$do_repl_require$arity$4 = (function (runtime,p__21576,done,error){
var map__21577 = p__21576;
var map__21577__$1 = cljs.core.__destructure_map(map__21577);
var msg = map__21577__$1;
var sources = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21577__$1,new cljs.core.Keyword(null,"sources","sources",-321166424));
var reload_namespaces = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21577__$1,new cljs.core.Keyword(null,"reload-namespaces","reload-namespaces",250210134));
var js_requires = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21577__$1,new cljs.core.Keyword(null,"js-requires","js-requires",-1311472051));
var runtime__$1 = this;
var sources_to_load = cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentVector.EMPTY,cljs.core.remove.cljs$core$IFn$_invoke$arity$2((function (p__21578){
var map__21579 = p__21578;
var map__21579__$1 = cljs.core.__destructure_map(map__21579);
var src = map__21579__$1;
var provides = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21579__$1,new cljs.core.Keyword(null,"provides","provides",-1634397992));
var and__5140__auto__ = shadow.cljs.devtools.client.env.src_is_loaded_QMARK_(src);
if(cljs.core.truth_(and__5140__auto__)){
return cljs.core.not(cljs.core.some(reload_namespaces,provides));
} else {
return and__5140__auto__;
}
}),sources));
if(cljs.core.not(cljs.core.seq(sources_to_load))){
var G__21580 = cljs.core.PersistentVector.EMPTY;
return (done.cljs$core$IFn$_invoke$arity$1 ? done.cljs$core$IFn$_invoke$arity$1(G__21580) : done.call(null,G__21580));
} else {
return shadow.remote.runtime.shared.call.cljs$core$IFn$_invoke$arity$3(runtime__$1,new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"op","op",-1882987955),new cljs.core.Keyword(null,"cljs-load-sources","cljs-load-sources",-1458295962),new cljs.core.Keyword(null,"to","to",192099007),shadow.cljs.devtools.client.env.worker_client_id,new cljs.core.Keyword(null,"sources","sources",-321166424),cljs.core.into.cljs$core$IFn$_invoke$arity$3(cljs.core.PersistentVector.EMPTY,cljs.core.map.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"resource-id","resource-id",-1308422582)),sources_to_load)], null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"cljs-sources","cljs-sources",31121610),(function (p__21581){
var map__21582 = p__21581;
var map__21582__$1 = cljs.core.__destructure_map(map__21582);
var msg__$1 = map__21582__$1;
var sources__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21582__$1,new cljs.core.Keyword(null,"sources","sources",-321166424));
try{shadow.cljs.devtools.client.browser.do_js_load(sources__$1);

if(cljs.core.seq(js_requires)){
shadow.cljs.devtools.client.browser.do_js_requires(js_requires);
} else {
}

return (done.cljs$core$IFn$_invoke$arity$1 ? done.cljs$core$IFn$_invoke$arity$1(sources_to_load) : done.call(null,sources_to_load));
}catch (e21583){var ex = e21583;
return (error.cljs$core$IFn$_invoke$arity$1 ? error.cljs$core$IFn$_invoke$arity$1(ex) : error.call(null,ex));
}})], null));
}
}));

shadow.cljs.devtools.client.shared.add_plugin_BANG_(new cljs.core.Keyword("shadow.cljs.devtools.client.browser","client","shadow.cljs.devtools.client.browser/client",-1461019282),cljs.core.PersistentHashSet.EMPTY,(function (p__21584){
var map__21585 = p__21584;
var map__21585__$1 = cljs.core.__destructure_map(map__21585);
var env = map__21585__$1;
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21585__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
var svc = new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"runtime","runtime",-1331573996),runtime], null);
shadow.remote.runtime.api.add_extension(runtime,new cljs.core.Keyword("shadow.cljs.devtools.client.browser","client","shadow.cljs.devtools.client.browser/client",-1461019282),new cljs.core.PersistentArrayMap(null, 4, [new cljs.core.Keyword(null,"on-welcome","on-welcome",1895317125),(function (){
cljs.core.reset_BANG_(shadow.cljs.devtools.client.browser.ws_was_welcome_ref,true);

shadow.cljs.devtools.client.hud.connection_error_clear_BANG_();

shadow.cljs.devtools.client.env.patch_goog_BANG_();

return shadow.cljs.devtools.client.browser.devtools_msg((""+"#"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"client-id","client-id",-464622140).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(new cljs.core.Keyword(null,"state-ref","state-ref",2127874952).cljs$core$IFn$_invoke$arity$1(runtime))))+" ready!"));
}),new cljs.core.Keyword(null,"on-disconnect","on-disconnect",-809021814),(function (e){
if(cljs.core.truth_(cljs.core.deref(shadow.cljs.devtools.client.browser.ws_was_welcome_ref))){
shadow.cljs.devtools.client.hud.connection_error("The Websocket connection was closed!");

return cljs.core.reset_BANG_(shadow.cljs.devtools.client.browser.ws_was_welcome_ref,false);
} else {
return null;
}
}),new cljs.core.Keyword(null,"on-reconnect","on-reconnect",1239988702),(function (e){
return shadow.cljs.devtools.client.hud.connection_error("Reconnecting ...");
}),new cljs.core.Keyword(null,"ops","ops",1237330063),new cljs.core.PersistentArrayMap(null, 7, [new cljs.core.Keyword(null,"access-denied","access-denied",959449406),(function (msg){
cljs.core.reset_BANG_(shadow.cljs.devtools.client.browser.ws_was_welcome_ref,false);

return shadow.cljs.devtools.client.hud.connection_error((""+"Stale Output! Your loaded JS was not produced by the running shadow-cljs instance."+" Is the watch for this build running?"));
}),new cljs.core.Keyword(null,"cljs-asset-update","cljs-asset-update",1224093028),(function (msg){
return shadow.cljs.devtools.client.browser.handle_asset_update(msg);
}),new cljs.core.Keyword(null,"cljs-build-configure","cljs-build-configure",-2089891268),(function (msg){
return null;
}),new cljs.core.Keyword(null,"cljs-build-start","cljs-build-start",-725781241),(function (msg){
shadow.cljs.devtools.client.hud.hud_hide();

shadow.cljs.devtools.client.hud.load_start();

return shadow.cljs.devtools.client.env.run_custom_notify_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(msg,new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"build-start","build-start",-959649480)));
}),new cljs.core.Keyword(null,"cljs-build-complete","cljs-build-complete",273626153),(function (msg){
var msg__$1 = shadow.cljs.devtools.client.env.add_warnings_to_info(msg);
shadow.cljs.devtools.client.hud.connection_error_clear_BANG_();

shadow.cljs.devtools.client.hud.hud_warnings(msg__$1);

shadow.cljs.devtools.client.browser.handle_build_complete(runtime,msg__$1);

return shadow.cljs.devtools.client.env.run_custom_notify_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(msg__$1,new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"build-complete","build-complete",-501868472)));
}),new cljs.core.Keyword(null,"cljs-build-failure","cljs-build-failure",1718154990),(function (msg){
shadow.cljs.devtools.client.hud.load_end();

shadow.cljs.devtools.client.hud.hud_error(msg);

return shadow.cljs.devtools.client.env.run_custom_notify_BANG_(cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(msg,new cljs.core.Keyword(null,"type","type",1174270348),new cljs.core.Keyword(null,"build-failure","build-failure",-2107487466)));
}),new cljs.core.Keyword("shadow.cljs.devtools.client.env","worker-notify","shadow.cljs.devtools.client.env/worker-notify",-1456820670),(function (p__21592){
var map__21593 = p__21592;
var map__21593__$1 = cljs.core.__destructure_map(map__21593);
var event_op = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21593__$1,new cljs.core.Keyword(null,"event-op","event-op",200358057));
var client_id = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21593__$1,new cljs.core.Keyword(null,"client-id","client-id",-464622140));
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"client-disconnect","client-disconnect",640227957),event_op)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(client_id,shadow.cljs.devtools.client.env.worker_client_id)))){
shadow.cljs.devtools.client.hud.connection_error_clear_BANG_();

return shadow.cljs.devtools.client.hud.connection_error("The watch for this build was stopped!");
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"client-connect","client-connect",-1113973888),event_op)){
shadow.cljs.devtools.client.hud.connection_error_clear_BANG_();

return shadow.cljs.devtools.client.hud.connection_error("The watch for this build was restarted. Reload required!");
} else {
return null;
}
}
})], null)], null));

return svc;
}),(function (p__21594){
var map__21595 = p__21594;
var map__21595__$1 = cljs.core.__destructure_map(map__21595);
var svc = map__21595__$1;
var runtime = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__21595__$1,new cljs.core.Keyword(null,"runtime","runtime",-1331573996));
return shadow.remote.runtime.api.del_extension(runtime,new cljs.core.Keyword("shadow.cljs.devtools.client.browser","client","shadow.cljs.devtools.client.browser/client",-1461019282));
}));

shadow.cljs.devtools.client.shared.init_runtime_BANG_(shadow.cljs.devtools.client.browser.client_info,shadow.cljs.devtools.client.websocket.start,shadow.cljs.devtools.client.websocket.send,shadow.cljs.devtools.client.websocket.stop);
} else {
}

//# sourceMappingURL=shadow.cljs.devtools.client.browser.js.map
