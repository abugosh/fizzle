goog.provide('shadow.dom');
shadow.dom.transition_supported_QMARK_ = true;

/**
 * @interface
 */
shadow.dom.IElement = function(){};

var shadow$dom$IElement$_to_dom$dyn_12838 = (function (this$){
var x__5498__auto__ = (((this$ == null))?null:this$);
var m__5499__auto__ = (shadow.dom._to_dom[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$1(this$) : m__5499__auto__.call(null,this$));
} else {
var m__5497__auto__ = (shadow.dom._to_dom["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$1(this$) : m__5497__auto__.call(null,this$));
} else {
throw cljs.core.missing_protocol("IElement.-to-dom",this$);
}
}
});
shadow.dom._to_dom = (function shadow$dom$_to_dom(this$){
if((((!((this$ == null)))) && ((!((this$.shadow$dom$IElement$_to_dom$arity$1 == null)))))){
return this$.shadow$dom$IElement$_to_dom$arity$1(this$);
} else {
return shadow$dom$IElement$_to_dom$dyn_12838(this$);
}
});


/**
 * @interface
 */
shadow.dom.SVGElement = function(){};

var shadow$dom$SVGElement$_to_svg$dyn_12843 = (function (this$){
var x__5498__auto__ = (((this$ == null))?null:this$);
var m__5499__auto__ = (shadow.dom._to_svg[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$1(this$) : m__5499__auto__.call(null,this$));
} else {
var m__5497__auto__ = (shadow.dom._to_svg["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$1(this$) : m__5497__auto__.call(null,this$));
} else {
throw cljs.core.missing_protocol("SVGElement.-to-svg",this$);
}
}
});
shadow.dom._to_svg = (function shadow$dom$_to_svg(this$){
if((((!((this$ == null)))) && ((!((this$.shadow$dom$SVGElement$_to_svg$arity$1 == null)))))){
return this$.shadow$dom$SVGElement$_to_svg$arity$1(this$);
} else {
return shadow$dom$SVGElement$_to_svg$dyn_12843(this$);
}
});

shadow.dom.lazy_native_coll_seq = (function shadow$dom$lazy_native_coll_seq(coll,idx){
if((idx < coll.length)){
return (new cljs.core.LazySeq(null,(function (){
return cljs.core.cons((coll[idx]),(function (){var G__11740 = coll;
var G__11741 = (idx + (1));
return (shadow.dom.lazy_native_coll_seq.cljs$core$IFn$_invoke$arity$2 ? shadow.dom.lazy_native_coll_seq.cljs$core$IFn$_invoke$arity$2(G__11740,G__11741) : shadow.dom.lazy_native_coll_seq.call(null,G__11740,G__11741));
})());
}),null,null));
} else {
return null;
}
});

/**
* @constructor
 * @implements {cljs.core.IIndexed}
 * @implements {cljs.core.ICounted}
 * @implements {cljs.core.ISeqable}
 * @implements {cljs.core.IDeref}
 * @implements {shadow.dom.IElement}
*/
shadow.dom.NativeColl = (function (coll){
this.coll = coll;
this.cljs$lang$protocol_mask$partition0$ = 8421394;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(shadow.dom.NativeColl.prototype.cljs$core$IDeref$_deref$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
return self__.coll;
}));

(shadow.dom.NativeColl.prototype.cljs$core$IIndexed$_nth$arity$2 = (function (this$,n){
var self__ = this;
var this$__$1 = this;
return (self__.coll[n]);
}));

(shadow.dom.NativeColl.prototype.cljs$core$IIndexed$_nth$arity$3 = (function (this$,n,not_found){
var self__ = this;
var this$__$1 = this;
var or__5142__auto__ = (self__.coll[n]);
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return not_found;
}
}));

(shadow.dom.NativeColl.prototype.cljs$core$ICounted$_count$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
return self__.coll.length;
}));

(shadow.dom.NativeColl.prototype.cljs$core$ISeqable$_seq$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
return shadow.dom.lazy_native_coll_seq(self__.coll,(0));
}));

(shadow.dom.NativeColl.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(shadow.dom.NativeColl.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
return self__.coll;
}));

(shadow.dom.NativeColl.getBasis = (function (){
return new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"coll","coll",-1006698606,null)], null);
}));

(shadow.dom.NativeColl.cljs$lang$type = true);

(shadow.dom.NativeColl.cljs$lang$ctorStr = "shadow.dom/NativeColl");

(shadow.dom.NativeColl.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"shadow.dom/NativeColl");
}));

/**
 * Positional factory function for shadow.dom/NativeColl.
 */
shadow.dom.__GT_NativeColl = (function shadow$dom$__GT_NativeColl(coll){
return (new shadow.dom.NativeColl(coll));
});

shadow.dom.native_coll = (function shadow$dom$native_coll(coll){
return (new shadow.dom.NativeColl(coll));
});
shadow.dom.dom_node = (function shadow$dom$dom_node(el){
if((el == null)){
return null;
} else {
if((((!((el == null))))?((((false) || ((cljs.core.PROTOCOL_SENTINEL === el.shadow$dom$IElement$))))?true:false):false)){
return el.shadow$dom$IElement$_to_dom$arity$1(null);
} else {
if(typeof el === 'string'){
return document.createTextNode(el);
} else {
if(typeof el === 'number'){
return document.createTextNode((""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(el)));
} else {
return el;

}
}
}
}
});
shadow.dom.query_one = (function shadow$dom$query_one(var_args){
var G__11760 = arguments.length;
switch (G__11760) {
case 1:
return shadow.dom.query_one.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.query_one.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.query_one.cljs$core$IFn$_invoke$arity$1 = (function (sel){
return document.querySelector(sel);
}));

(shadow.dom.query_one.cljs$core$IFn$_invoke$arity$2 = (function (sel,root){
return shadow.dom.dom_node(root).querySelector(sel);
}));

(shadow.dom.query_one.cljs$lang$maxFixedArity = 2);

shadow.dom.query = (function shadow$dom$query(var_args){
var G__11765 = arguments.length;
switch (G__11765) {
case 1:
return shadow.dom.query.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.query.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.query.cljs$core$IFn$_invoke$arity$1 = (function (sel){
return (new shadow.dom.NativeColl(document.querySelectorAll(sel)));
}));

(shadow.dom.query.cljs$core$IFn$_invoke$arity$2 = (function (sel,root){
return (new shadow.dom.NativeColl(shadow.dom.dom_node(root).querySelectorAll(sel)));
}));

(shadow.dom.query.cljs$lang$maxFixedArity = 2);

shadow.dom.by_id = (function shadow$dom$by_id(var_args){
var G__11778 = arguments.length;
switch (G__11778) {
case 2:
return shadow.dom.by_id.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 1:
return shadow.dom.by_id.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.by_id.cljs$core$IFn$_invoke$arity$2 = (function (id,el){
return shadow.dom.dom_node(el).getElementById(id);
}));

(shadow.dom.by_id.cljs$core$IFn$_invoke$arity$1 = (function (id){
return document.getElementById(id);
}));

(shadow.dom.by_id.cljs$lang$maxFixedArity = 2);

shadow.dom.build = shadow.dom.dom_node;
shadow.dom.ev_stop = (function shadow$dom$ev_stop(var_args){
var G__11791 = arguments.length;
switch (G__11791) {
case 1:
return shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 4:
return shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$1 = (function (e){
if(cljs.core.truth_(e.stopPropagation)){
e.stopPropagation();

e.preventDefault();
} else {
(e.cancelBubble = true);

(e.returnValue = false);
}

return e;
}));

(shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$2 = (function (e,el){
shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$1(e);

return el;
}));

(shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$4 = (function (e,el,scope,owner){
shadow.dom.ev_stop.cljs$core$IFn$_invoke$arity$1(e);

return el;
}));

(shadow.dom.ev_stop.cljs$lang$maxFixedArity = 4);

/**
 * check wether a parent node (or the document) contains the child
 */
shadow.dom.contains_QMARK_ = (function shadow$dom$contains_QMARK_(var_args){
var G__11799 = arguments.length;
switch (G__11799) {
case 1:
return shadow.dom.contains_QMARK_.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.contains_QMARK_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.contains_QMARK_.cljs$core$IFn$_invoke$arity$1 = (function (el){
return goog.dom.contains(document,shadow.dom.dom_node(el));
}));

(shadow.dom.contains_QMARK_.cljs$core$IFn$_invoke$arity$2 = (function (parent,el){
return goog.dom.contains(shadow.dom.dom_node(parent),shadow.dom.dom_node(el));
}));

(shadow.dom.contains_QMARK_.cljs$lang$maxFixedArity = 2);

shadow.dom.add_class = (function shadow$dom$add_class(el,cls){
return goog.dom.classlist.add(shadow.dom.dom_node(el),cls);
});
shadow.dom.remove_class = (function shadow$dom$remove_class(el,cls){
return goog.dom.classlist.remove(shadow.dom.dom_node(el),cls);
});
shadow.dom.toggle_class = (function shadow$dom$toggle_class(var_args){
var G__11814 = arguments.length;
switch (G__11814) {
case 2:
return shadow.dom.toggle_class.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return shadow.dom.toggle_class.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.toggle_class.cljs$core$IFn$_invoke$arity$2 = (function (el,cls){
return goog.dom.classlist.toggle(shadow.dom.dom_node(el),cls);
}));

(shadow.dom.toggle_class.cljs$core$IFn$_invoke$arity$3 = (function (el,cls,v){
if(cljs.core.truth_(v)){
return shadow.dom.add_class(el,cls);
} else {
return shadow.dom.remove_class(el,cls);
}
}));

(shadow.dom.toggle_class.cljs$lang$maxFixedArity = 3);

shadow.dom.dom_listen = (cljs.core.truth_((function (){var or__5142__auto__ = (!((typeof document !== 'undefined')));
if(or__5142__auto__){
return or__5142__auto__;
} else {
return document.addEventListener;
}
})())?(function shadow$dom$dom_listen_good(el,ev,handler){
return el.addEventListener(ev,handler,false);
}):(function shadow$dom$dom_listen_ie(el,ev,handler){
try{return el.attachEvent((""+"on"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(ev)),(function (e){
return (handler.cljs$core$IFn$_invoke$arity$2 ? handler.cljs$core$IFn$_invoke$arity$2(e,el) : handler.call(null,e,el));
}));
}catch (e11838){if((e11838 instanceof Object)){
var e = e11838;
return console.log("didnt support attachEvent",el,e);
} else {
throw e11838;

}
}}));
shadow.dom.dom_listen_remove = (cljs.core.truth_((function (){var or__5142__auto__ = (!((typeof document !== 'undefined')));
if(or__5142__auto__){
return or__5142__auto__;
} else {
return document.removeEventListener;
}
})())?(function shadow$dom$dom_listen_remove_good(el,ev,handler){
return el.removeEventListener(ev,handler,false);
}):(function shadow$dom$dom_listen_remove_ie(el,ev,handler){
return el.detachEvent((""+"on"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(ev)),handler);
}));
shadow.dom.on_query = (function shadow$dom$on_query(root_el,ev,selector,handler){
var seq__11861 = cljs.core.seq(shadow.dom.query.cljs$core$IFn$_invoke$arity$2(selector,root_el));
var chunk__11862 = null;
var count__11863 = (0);
var i__11864 = (0);
while(true){
if((i__11864 < count__11863)){
var el = chunk__11862.cljs$core$IIndexed$_nth$arity$2(null,i__11864);
var handler_12902__$1 = ((function (seq__11861,chunk__11862,count__11863,i__11864,el){
return (function (e){
return (handler.cljs$core$IFn$_invoke$arity$2 ? handler.cljs$core$IFn$_invoke$arity$2(e,el) : handler.call(null,e,el));
});})(seq__11861,chunk__11862,count__11863,i__11864,el))
;
shadow.dom.dom_listen(el,cljs.core.name(ev),handler_12902__$1);


var G__12904 = seq__11861;
var G__12905 = chunk__11862;
var G__12906 = count__11863;
var G__12907 = (i__11864 + (1));
seq__11861 = G__12904;
chunk__11862 = G__12905;
count__11863 = G__12906;
i__11864 = G__12907;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__11861);
if(temp__5823__auto__){
var seq__11861__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__11861__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__11861__$1);
var G__12910 = cljs.core.chunk_rest(seq__11861__$1);
var G__12911 = c__5673__auto__;
var G__12912 = cljs.core.count(c__5673__auto__);
var G__12913 = (0);
seq__11861 = G__12910;
chunk__11862 = G__12911;
count__11863 = G__12912;
i__11864 = G__12913;
continue;
} else {
var el = cljs.core.first(seq__11861__$1);
var handler_12921__$1 = ((function (seq__11861,chunk__11862,count__11863,i__11864,el,seq__11861__$1,temp__5823__auto__){
return (function (e){
return (handler.cljs$core$IFn$_invoke$arity$2 ? handler.cljs$core$IFn$_invoke$arity$2(e,el) : handler.call(null,e,el));
});})(seq__11861,chunk__11862,count__11863,i__11864,el,seq__11861__$1,temp__5823__auto__))
;
shadow.dom.dom_listen(el,cljs.core.name(ev),handler_12921__$1);


var G__12923 = cljs.core.next(seq__11861__$1);
var G__12924 = null;
var G__12925 = (0);
var G__12926 = (0);
seq__11861 = G__12923;
chunk__11862 = G__12924;
count__11863 = G__12925;
i__11864 = G__12926;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.dom.on = (function shadow$dom$on(var_args){
var G__11895 = arguments.length;
switch (G__11895) {
case 3:
return shadow.dom.on.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
case 4:
return shadow.dom.on.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.on.cljs$core$IFn$_invoke$arity$3 = (function (el,ev,handler){
return shadow.dom.on.cljs$core$IFn$_invoke$arity$4(el,ev,handler,false);
}));

(shadow.dom.on.cljs$core$IFn$_invoke$arity$4 = (function (el,ev,handler,capture){
if(cljs.core.vector_QMARK_(ev)){
return shadow.dom.on_query(el,cljs.core.first(ev),cljs.core.second(ev),handler);
} else {
var handler__$1 = (function (e){
return (handler.cljs$core$IFn$_invoke$arity$2 ? handler.cljs$core$IFn$_invoke$arity$2(e,el) : handler.call(null,e,el));
});
return shadow.dom.dom_listen(shadow.dom.dom_node(el),cljs.core.name(ev),handler__$1);
}
}));

(shadow.dom.on.cljs$lang$maxFixedArity = 4);

shadow.dom.remove_event_handler = (function shadow$dom$remove_event_handler(el,ev,handler){
return shadow.dom.dom_listen_remove(shadow.dom.dom_node(el),cljs.core.name(ev),handler);
});
shadow.dom.add_event_listeners = (function shadow$dom$add_event_listeners(el,events){
var seq__11910 = cljs.core.seq(events);
var chunk__11911 = null;
var count__11912 = (0);
var i__11913 = (0);
while(true){
if((i__11913 < count__11912)){
var vec__11939 = chunk__11911.cljs$core$IIndexed$_nth$arity$2(null,i__11913);
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__11939,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__11939,(1),null);
shadow.dom.on.cljs$core$IFn$_invoke$arity$3(el,k,v);


var G__12947 = seq__11910;
var G__12948 = chunk__11911;
var G__12949 = count__11912;
var G__12950 = (i__11913 + (1));
seq__11910 = G__12947;
chunk__11911 = G__12948;
count__11912 = G__12949;
i__11913 = G__12950;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__11910);
if(temp__5823__auto__){
var seq__11910__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__11910__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__11910__$1);
var G__12961 = cljs.core.chunk_rest(seq__11910__$1);
var G__12962 = c__5673__auto__;
var G__12963 = cljs.core.count(c__5673__auto__);
var G__12964 = (0);
seq__11910 = G__12961;
chunk__11911 = G__12962;
count__11912 = G__12963;
i__11913 = G__12964;
continue;
} else {
var vec__11949 = cljs.core.first(seq__11910__$1);
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__11949,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__11949,(1),null);
shadow.dom.on.cljs$core$IFn$_invoke$arity$3(el,k,v);


var G__12967 = cljs.core.next(seq__11910__$1);
var G__12968 = null;
var G__12969 = (0);
var G__12970 = (0);
seq__11910 = G__12967;
chunk__11911 = G__12968;
count__11912 = G__12969;
i__11913 = G__12970;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.dom.set_style = (function shadow$dom$set_style(el,styles){
var dom = shadow.dom.dom_node(el);
var seq__11963 = cljs.core.seq(styles);
var chunk__11964 = null;
var count__11965 = (0);
var i__11966 = (0);
while(true){
if((i__11966 < count__11965)){
var vec__11991 = chunk__11964.cljs$core$IIndexed$_nth$arity$2(null,i__11966);
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__11991,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__11991,(1),null);
goog.style.setStyle(dom,cljs.core.name(k),(((v == null))?"":v));


var G__12977 = seq__11963;
var G__12978 = chunk__11964;
var G__12979 = count__11965;
var G__12980 = (i__11966 + (1));
seq__11963 = G__12977;
chunk__11964 = G__12978;
count__11965 = G__12979;
i__11966 = G__12980;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__11963);
if(temp__5823__auto__){
var seq__11963__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__11963__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__11963__$1);
var G__12983 = cljs.core.chunk_rest(seq__11963__$1);
var G__12984 = c__5673__auto__;
var G__12985 = cljs.core.count(c__5673__auto__);
var G__12986 = (0);
seq__11963 = G__12983;
chunk__11964 = G__12984;
count__11965 = G__12985;
i__11966 = G__12986;
continue;
} else {
var vec__12032 = cljs.core.first(seq__11963__$1);
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12032,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12032,(1),null);
goog.style.setStyle(dom,cljs.core.name(k),(((v == null))?"":v));


var G__12998 = cljs.core.next(seq__11963__$1);
var G__12999 = null;
var G__13000 = (0);
var G__13001 = (0);
seq__11963 = G__12998;
chunk__11964 = G__12999;
count__11965 = G__13000;
i__11966 = G__13001;
continue;
}
} else {
return null;
}
}
break;
}
});
shadow.dom.set_attr_STAR_ = (function shadow$dom$set_attr_STAR_(el,key,value){
var G__12054_13003 = key;
var G__12054_13004__$1 = (((G__12054_13003 instanceof cljs.core.Keyword))?G__12054_13003.fqn:null);
switch (G__12054_13004__$1) {
case "id":
(el.id = (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(value)));

break;
case "class":
(el.className = (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(value)));

break;
case "for":
(el.htmlFor = value);

break;
case "cellpadding":
el.setAttribute("cellPadding",value);

break;
case "cellspacing":
el.setAttribute("cellSpacing",value);

break;
case "colspan":
el.setAttribute("colSpan",value);

break;
case "frameborder":
el.setAttribute("frameBorder",value);

break;
case "height":
el.setAttribute("height",value);

break;
case "maxlength":
el.setAttribute("maxLength",value);

break;
case "role":
el.setAttribute("role",value);

break;
case "rowspan":
el.setAttribute("rowSpan",value);

break;
case "type":
el.setAttribute("type",value);

break;
case "usemap":
el.setAttribute("useMap",value);

break;
case "valign":
el.setAttribute("vAlign",value);

break;
case "width":
el.setAttribute("width",value);

break;
case "on":
shadow.dom.add_event_listeners(el,value);

break;
case "style":
if((value == null)){
} else {
if(typeof value === 'string'){
el.setAttribute("style",value);
} else {
if(cljs.core.map_QMARK_(value)){
shadow.dom.set_style(el,value);
} else {
goog.style.setStyle(el,value);

}
}
}

break;
default:
var ks_13016 = cljs.core.name(key);
if(cljs.core.truth_((function (){var or__5142__auto__ = goog.string.startsWith(ks_13016,"data-");
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return goog.string.startsWith(ks_13016,"aria-");
}
})())){
el.setAttribute(ks_13016,value);
} else {
(el[ks_13016] = value);
}

}

return el;
});
shadow.dom.set_attrs = (function shadow$dom$set_attrs(el,attrs){
return cljs.core.reduce_kv((function (el__$1,key,value){
shadow.dom.set_attr_STAR_(el__$1,key,value);

return el__$1;
}),shadow.dom.dom_node(el),attrs);
});
shadow.dom.set_attr = (function shadow$dom$set_attr(el,key,value){
return shadow.dom.set_attr_STAR_(shadow.dom.dom_node(el),key,value);
});
shadow.dom.has_class_QMARK_ = (function shadow$dom$has_class_QMARK_(el,cls){
return goog.dom.classlist.contains(shadow.dom.dom_node(el),cls);
});
shadow.dom.merge_class_string = (function shadow$dom$merge_class_string(current,extra_class){
if(cljs.core.seq(current)){
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(current)+" "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(extra_class));
} else {
return extra_class;
}
});
shadow.dom.parse_tag = (function shadow$dom$parse_tag(spec){
var spec__$1 = cljs.core.name(spec);
var fdot = spec__$1.indexOf(".");
var fhash = spec__$1.indexOf("#");
if(((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2((-1),fdot)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2((-1),fhash)))){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [spec__$1,null,null], null);
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2((-1),fhash)){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [spec__$1.substring((0),fdot),null,clojure.string.replace(spec__$1.substring((fdot + (1))),/\./," ")], null);
} else {
if(cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2((-1),fdot)){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [spec__$1.substring((0),fhash),spec__$1.substring((fhash + (1))),null], null);
} else {
if((fhash > fdot)){
throw (""+"cant have id after class?"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(spec__$1));
} else {
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [spec__$1.substring((0),fhash),spec__$1.substring((fhash + (1)),fdot),clojure.string.replace(spec__$1.substring((fdot + (1))),/\./," ")], null);

}
}
}
}
});
shadow.dom.create_dom_node = (function shadow$dom$create_dom_node(tag_def,p__12108){
var map__12110 = p__12108;
var map__12110__$1 = cljs.core.__destructure_map(map__12110);
var props = map__12110__$1;
var class$ = cljs.core.get.cljs$core$IFn$_invoke$arity$2(map__12110__$1,new cljs.core.Keyword(null,"class","class",-2030961996));
var tag_props = ({});
var vec__12116 = shadow.dom.parse_tag(tag_def);
var tag_name = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12116,(0),null);
var tag_id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12116,(1),null);
var tag_classes = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12116,(2),null);
if(cljs.core.truth_(tag_id)){
(tag_props["id"] = tag_id);
} else {
}

if(cljs.core.truth_(tag_classes)){
(tag_props["class"] = shadow.dom.merge_class_string(class$,tag_classes));
} else {
}

var G__12126 = goog.dom.createDom(tag_name,tag_props);
shadow.dom.set_attrs(G__12126,cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(props,new cljs.core.Keyword(null,"class","class",-2030961996)));

return G__12126;
});
shadow.dom.append = (function shadow$dom$append(var_args){
var G__12141 = arguments.length;
switch (G__12141) {
case 1:
return shadow.dom.append.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.append.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.append.cljs$core$IFn$_invoke$arity$1 = (function (node){
if(cljs.core.truth_(node)){
var temp__5823__auto__ = shadow.dom.dom_node(node);
if(cljs.core.truth_(temp__5823__auto__)){
var n = temp__5823__auto__;
document.body.appendChild(n);

return n;
} else {
return null;
}
} else {
return null;
}
}));

(shadow.dom.append.cljs$core$IFn$_invoke$arity$2 = (function (el,node){
if(cljs.core.truth_(node)){
var temp__5823__auto__ = shadow.dom.dom_node(node);
if(cljs.core.truth_(temp__5823__auto__)){
var n = temp__5823__auto__;
shadow.dom.dom_node(el).appendChild(n);

return n;
} else {
return null;
}
} else {
return null;
}
}));

(shadow.dom.append.cljs$lang$maxFixedArity = 2);

shadow.dom.destructure_node = (function shadow$dom$destructure_node(create_fn,p__12157){
var vec__12158 = p__12157;
var seq__12159 = cljs.core.seq(vec__12158);
var first__12160 = cljs.core.first(seq__12159);
var seq__12159__$1 = cljs.core.next(seq__12159);
var nn = first__12160;
var first__12160__$1 = cljs.core.first(seq__12159__$1);
var seq__12159__$2 = cljs.core.next(seq__12159__$1);
var np = first__12160__$1;
var nc = seq__12159__$2;
var node = vec__12158;
if((nn instanceof cljs.core.Keyword)){
} else {
throw cljs.core.ex_info.cljs$core$IFn$_invoke$arity$2("invalid dom node",new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"node","node",581201198),node], null));
}

if((((np == null)) && ((nc == null)))){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(function (){var G__12171 = nn;
var G__12172 = cljs.core.PersistentArrayMap.EMPTY;
return (create_fn.cljs$core$IFn$_invoke$arity$2 ? create_fn.cljs$core$IFn$_invoke$arity$2(G__12171,G__12172) : create_fn.call(null,G__12171,G__12172));
})(),cljs.core.List.EMPTY], null);
} else {
if(cljs.core.map_QMARK_(np)){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(create_fn.cljs$core$IFn$_invoke$arity$2 ? create_fn.cljs$core$IFn$_invoke$arity$2(nn,np) : create_fn.call(null,nn,np)),nc], null);
} else {
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(function (){var G__12178 = nn;
var G__12179 = cljs.core.PersistentArrayMap.EMPTY;
return (create_fn.cljs$core$IFn$_invoke$arity$2 ? create_fn.cljs$core$IFn$_invoke$arity$2(G__12178,G__12179) : create_fn.call(null,G__12178,G__12179));
})(),cljs.core.conj.cljs$core$IFn$_invoke$arity$2(nc,np)], null);

}
}
});
shadow.dom.make_dom_node = (function shadow$dom$make_dom_node(structure){
var vec__12180 = shadow.dom.destructure_node(shadow.dom.create_dom_node,structure);
var node = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12180,(0),null);
var node_children = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12180,(1),null);
var seq__12183_13104 = cljs.core.seq(node_children);
var chunk__12184_13105 = null;
var count__12185_13106 = (0);
var i__12186_13107 = (0);
while(true){
if((i__12186_13107 < count__12185_13106)){
var child_struct_13111 = chunk__12184_13105.cljs$core$IIndexed$_nth$arity$2(null,i__12186_13107);
var children_13112 = shadow.dom.dom_node(child_struct_13111);
if(cljs.core.seq_QMARK_(children_13112)){
var seq__12218_13113 = cljs.core.seq(cljs.core.map.cljs$core$IFn$_invoke$arity$2(shadow.dom.dom_node,children_13112));
var chunk__12220_13114 = null;
var count__12221_13115 = (0);
var i__12222_13116 = (0);
while(true){
if((i__12222_13116 < count__12221_13115)){
var child_13119 = chunk__12220_13114.cljs$core$IIndexed$_nth$arity$2(null,i__12222_13116);
if(cljs.core.truth_(child_13119)){
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,child_13119);


var G__13132 = seq__12218_13113;
var G__13133 = chunk__12220_13114;
var G__13134 = count__12221_13115;
var G__13135 = (i__12222_13116 + (1));
seq__12218_13113 = G__13132;
chunk__12220_13114 = G__13133;
count__12221_13115 = G__13134;
i__12222_13116 = G__13135;
continue;
} else {
var G__13137 = seq__12218_13113;
var G__13138 = chunk__12220_13114;
var G__13139 = count__12221_13115;
var G__13140 = (i__12222_13116 + (1));
seq__12218_13113 = G__13137;
chunk__12220_13114 = G__13138;
count__12221_13115 = G__13139;
i__12222_13116 = G__13140;
continue;
}
} else {
var temp__5823__auto___13142 = cljs.core.seq(seq__12218_13113);
if(temp__5823__auto___13142){
var seq__12218_13143__$1 = temp__5823__auto___13142;
if(cljs.core.chunked_seq_QMARK_(seq__12218_13143__$1)){
var c__5673__auto___13144 = cljs.core.chunk_first(seq__12218_13143__$1);
var G__13145 = cljs.core.chunk_rest(seq__12218_13143__$1);
var G__13146 = c__5673__auto___13144;
var G__13147 = cljs.core.count(c__5673__auto___13144);
var G__13148 = (0);
seq__12218_13113 = G__13145;
chunk__12220_13114 = G__13146;
count__12221_13115 = G__13147;
i__12222_13116 = G__13148;
continue;
} else {
var child_13150 = cljs.core.first(seq__12218_13143__$1);
if(cljs.core.truth_(child_13150)){
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,child_13150);


var G__13152 = cljs.core.next(seq__12218_13143__$1);
var G__13153 = null;
var G__13154 = (0);
var G__13155 = (0);
seq__12218_13113 = G__13152;
chunk__12220_13114 = G__13153;
count__12221_13115 = G__13154;
i__12222_13116 = G__13155;
continue;
} else {
var G__13157 = cljs.core.next(seq__12218_13143__$1);
var G__13158 = null;
var G__13159 = (0);
var G__13160 = (0);
seq__12218_13113 = G__13157;
chunk__12220_13114 = G__13158;
count__12221_13115 = G__13159;
i__12222_13116 = G__13160;
continue;
}
}
} else {
}
}
break;
}
} else {
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,children_13112);
}


var G__13162 = seq__12183_13104;
var G__13163 = chunk__12184_13105;
var G__13164 = count__12185_13106;
var G__13165 = (i__12186_13107 + (1));
seq__12183_13104 = G__13162;
chunk__12184_13105 = G__13163;
count__12185_13106 = G__13164;
i__12186_13107 = G__13165;
continue;
} else {
var temp__5823__auto___13166 = cljs.core.seq(seq__12183_13104);
if(temp__5823__auto___13166){
var seq__12183_13167__$1 = temp__5823__auto___13166;
if(cljs.core.chunked_seq_QMARK_(seq__12183_13167__$1)){
var c__5673__auto___13169 = cljs.core.chunk_first(seq__12183_13167__$1);
var G__13170 = cljs.core.chunk_rest(seq__12183_13167__$1);
var G__13171 = c__5673__auto___13169;
var G__13172 = cljs.core.count(c__5673__auto___13169);
var G__13173 = (0);
seq__12183_13104 = G__13170;
chunk__12184_13105 = G__13171;
count__12185_13106 = G__13172;
i__12186_13107 = G__13173;
continue;
} else {
var child_struct_13174 = cljs.core.first(seq__12183_13167__$1);
var children_13175 = shadow.dom.dom_node(child_struct_13174);
if(cljs.core.seq_QMARK_(children_13175)){
var seq__12239_13180 = cljs.core.seq(cljs.core.map.cljs$core$IFn$_invoke$arity$2(shadow.dom.dom_node,children_13175));
var chunk__12241_13181 = null;
var count__12242_13182 = (0);
var i__12243_13183 = (0);
while(true){
if((i__12243_13183 < count__12242_13182)){
var child_13188 = chunk__12241_13181.cljs$core$IIndexed$_nth$arity$2(null,i__12243_13183);
if(cljs.core.truth_(child_13188)){
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,child_13188);


var G__13193 = seq__12239_13180;
var G__13194 = chunk__12241_13181;
var G__13195 = count__12242_13182;
var G__13196 = (i__12243_13183 + (1));
seq__12239_13180 = G__13193;
chunk__12241_13181 = G__13194;
count__12242_13182 = G__13195;
i__12243_13183 = G__13196;
continue;
} else {
var G__13198 = seq__12239_13180;
var G__13199 = chunk__12241_13181;
var G__13200 = count__12242_13182;
var G__13201 = (i__12243_13183 + (1));
seq__12239_13180 = G__13198;
chunk__12241_13181 = G__13199;
count__12242_13182 = G__13200;
i__12243_13183 = G__13201;
continue;
}
} else {
var temp__5823__auto___13202__$1 = cljs.core.seq(seq__12239_13180);
if(temp__5823__auto___13202__$1){
var seq__12239_13204__$1 = temp__5823__auto___13202__$1;
if(cljs.core.chunked_seq_QMARK_(seq__12239_13204__$1)){
var c__5673__auto___13205 = cljs.core.chunk_first(seq__12239_13204__$1);
var G__13207 = cljs.core.chunk_rest(seq__12239_13204__$1);
var G__13208 = c__5673__auto___13205;
var G__13209 = cljs.core.count(c__5673__auto___13205);
var G__13210 = (0);
seq__12239_13180 = G__13207;
chunk__12241_13181 = G__13208;
count__12242_13182 = G__13209;
i__12243_13183 = G__13210;
continue;
} else {
var child_13213 = cljs.core.first(seq__12239_13204__$1);
if(cljs.core.truth_(child_13213)){
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,child_13213);


var G__13214 = cljs.core.next(seq__12239_13204__$1);
var G__13215 = null;
var G__13216 = (0);
var G__13217 = (0);
seq__12239_13180 = G__13214;
chunk__12241_13181 = G__13215;
count__12242_13182 = G__13216;
i__12243_13183 = G__13217;
continue;
} else {
var G__13218 = cljs.core.next(seq__12239_13204__$1);
var G__13219 = null;
var G__13220 = (0);
var G__13221 = (0);
seq__12239_13180 = G__13218;
chunk__12241_13181 = G__13219;
count__12242_13182 = G__13220;
i__12243_13183 = G__13221;
continue;
}
}
} else {
}
}
break;
}
} else {
shadow.dom.append.cljs$core$IFn$_invoke$arity$2(node,children_13175);
}


var G__13223 = cljs.core.next(seq__12183_13167__$1);
var G__13224 = null;
var G__13225 = (0);
var G__13226 = (0);
seq__12183_13104 = G__13223;
chunk__12184_13105 = G__13224;
count__12185_13106 = G__13225;
i__12186_13107 = G__13226;
continue;
}
} else {
}
}
break;
}

return node;
});
(cljs.core.Keyword.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.Keyword.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return shadow.dom.make_dom_node(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [this$__$1], null));
}));

(cljs.core.PersistentVector.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.PersistentVector.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return shadow.dom.make_dom_node(this$__$1);
}));

(cljs.core.LazySeq.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.LazySeq.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return cljs.core.map.cljs$core$IFn$_invoke$arity$2(shadow.dom._to_dom,this$__$1);
}));
if(cljs.core.truth_(((typeof HTMLElement) != 'undefined'))){
(HTMLElement.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(HTMLElement.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return this$__$1;
}));
} else {
}
if(cljs.core.truth_(((typeof DocumentFragment) != 'undefined'))){
(DocumentFragment.prototype.shadow$dom$IElement$ = cljs.core.PROTOCOL_SENTINEL);

(DocumentFragment.prototype.shadow$dom$IElement$_to_dom$arity$1 = (function (this$){
var this$__$1 = this;
return this$__$1;
}));
} else {
}
/**
 * clear node children
 */
shadow.dom.reset = (function shadow$dom$reset(node){
return goog.dom.removeChildren(shadow.dom.dom_node(node));
});
shadow.dom.remove = (function shadow$dom$remove(node){
if((((!((node == null))))?(((((node.cljs$lang$protocol_mask$partition0$ & (8388608))) || ((cljs.core.PROTOCOL_SENTINEL === node.cljs$core$ISeqable$))))?true:false):false)){
var seq__12296 = cljs.core.seq(node);
var chunk__12297 = null;
var count__12298 = (0);
var i__12299 = (0);
while(true){
if((i__12299 < count__12298)){
var n = chunk__12297.cljs$core$IIndexed$_nth$arity$2(null,i__12299);
(shadow.dom.remove.cljs$core$IFn$_invoke$arity$1 ? shadow.dom.remove.cljs$core$IFn$_invoke$arity$1(n) : shadow.dom.remove.call(null,n));


var G__13278 = seq__12296;
var G__13279 = chunk__12297;
var G__13280 = count__12298;
var G__13281 = (i__12299 + (1));
seq__12296 = G__13278;
chunk__12297 = G__13279;
count__12298 = G__13280;
i__12299 = G__13281;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__12296);
if(temp__5823__auto__){
var seq__12296__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__12296__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__12296__$1);
var G__13283 = cljs.core.chunk_rest(seq__12296__$1);
var G__13284 = c__5673__auto__;
var G__13285 = cljs.core.count(c__5673__auto__);
var G__13286 = (0);
seq__12296 = G__13283;
chunk__12297 = G__13284;
count__12298 = G__13285;
i__12299 = G__13286;
continue;
} else {
var n = cljs.core.first(seq__12296__$1);
(shadow.dom.remove.cljs$core$IFn$_invoke$arity$1 ? shadow.dom.remove.cljs$core$IFn$_invoke$arity$1(n) : shadow.dom.remove.call(null,n));


var G__13288 = cljs.core.next(seq__12296__$1);
var G__13289 = null;
var G__13290 = (0);
var G__13291 = (0);
seq__12296 = G__13288;
chunk__12297 = G__13289;
count__12298 = G__13290;
i__12299 = G__13291;
continue;
}
} else {
return null;
}
}
break;
}
} else {
return goog.dom.removeNode(node);
}
});
shadow.dom.replace_node = (function shadow$dom$replace_node(old,new$){
return goog.dom.replaceNode(shadow.dom.dom_node(new$),shadow.dom.dom_node(old));
});
shadow.dom.text = (function shadow$dom$text(var_args){
var G__12316 = arguments.length;
switch (G__12316) {
case 2:
return shadow.dom.text.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 1:
return shadow.dom.text.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.text.cljs$core$IFn$_invoke$arity$2 = (function (el,new_text){
return (shadow.dom.dom_node(el).innerText = new_text);
}));

(shadow.dom.text.cljs$core$IFn$_invoke$arity$1 = (function (el){
return shadow.dom.dom_node(el).innerText;
}));

(shadow.dom.text.cljs$lang$maxFixedArity = 2);

shadow.dom.check = (function shadow$dom$check(var_args){
var G__12325 = arguments.length;
switch (G__12325) {
case 1:
return shadow.dom.check.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.check.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.check.cljs$core$IFn$_invoke$arity$1 = (function (el){
return shadow.dom.check.cljs$core$IFn$_invoke$arity$2(el,true);
}));

(shadow.dom.check.cljs$core$IFn$_invoke$arity$2 = (function (el,checked){
return (shadow.dom.dom_node(el).checked = checked);
}));

(shadow.dom.check.cljs$lang$maxFixedArity = 2);

shadow.dom.checked_QMARK_ = (function shadow$dom$checked_QMARK_(el){
return shadow.dom.dom_node(el).checked;
});
shadow.dom.form_elements = (function shadow$dom$form_elements(el){
return (new shadow.dom.NativeColl(shadow.dom.dom_node(el).elements));
});
shadow.dom.children = (function shadow$dom$children(el){
return (new shadow.dom.NativeColl(shadow.dom.dom_node(el).children));
});
shadow.dom.child_nodes = (function shadow$dom$child_nodes(el){
return (new shadow.dom.NativeColl(shadow.dom.dom_node(el).childNodes));
});
shadow.dom.attr = (function shadow$dom$attr(var_args){
var G__12343 = arguments.length;
switch (G__12343) {
case 2:
return shadow.dom.attr.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return shadow.dom.attr.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.attr.cljs$core$IFn$_invoke$arity$2 = (function (el,key){
return shadow.dom.dom_node(el).getAttribute(cljs.core.name(key));
}));

(shadow.dom.attr.cljs$core$IFn$_invoke$arity$3 = (function (el,key,default$){
var or__5142__auto__ = shadow.dom.dom_node(el).getAttribute(cljs.core.name(key));
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return default$;
}
}));

(shadow.dom.attr.cljs$lang$maxFixedArity = 3);

shadow.dom.del_attr = (function shadow$dom$del_attr(el,key){
return shadow.dom.dom_node(el).removeAttribute(cljs.core.name(key));
});
shadow.dom.data = (function shadow$dom$data(el,key){
return shadow.dom.dom_node(el).getAttribute((""+"data-"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.name(key))));
});
shadow.dom.set_data = (function shadow$dom$set_data(el,key,value){
return shadow.dom.dom_node(el).setAttribute((""+"data-"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.name(key))),(""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(value)));
});
shadow.dom.set_html = (function shadow$dom$set_html(node,text){
return (shadow.dom.dom_node(node).innerHTML = text);
});
shadow.dom.get_html = (function shadow$dom$get_html(node){
return shadow.dom.dom_node(node).innerHTML;
});
shadow.dom.fragment = (function shadow$dom$fragment(var_args){
var args__5882__auto__ = [];
var len__5876__auto___13332 = arguments.length;
var i__5877__auto___13333 = (0);
while(true){
if((i__5877__auto___13333 < len__5876__auto___13332)){
args__5882__auto__.push((arguments[i__5877__auto___13333]));

var G__13343 = (i__5877__auto___13333 + (1));
i__5877__auto___13333 = G__13343;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return shadow.dom.fragment.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(shadow.dom.fragment.cljs$core$IFn$_invoke$arity$variadic = (function (nodes){
var fragment = document.createDocumentFragment();
var seq__12363_13346 = cljs.core.seq(nodes);
var chunk__12364_13347 = null;
var count__12365_13348 = (0);
var i__12366_13349 = (0);
while(true){
if((i__12366_13349 < count__12365_13348)){
var node_13351 = chunk__12364_13347.cljs$core$IIndexed$_nth$arity$2(null,i__12366_13349);
fragment.appendChild(shadow.dom._to_dom(node_13351));


var G__13353 = seq__12363_13346;
var G__13354 = chunk__12364_13347;
var G__13355 = count__12365_13348;
var G__13356 = (i__12366_13349 + (1));
seq__12363_13346 = G__13353;
chunk__12364_13347 = G__13354;
count__12365_13348 = G__13355;
i__12366_13349 = G__13356;
continue;
} else {
var temp__5823__auto___13357 = cljs.core.seq(seq__12363_13346);
if(temp__5823__auto___13357){
var seq__12363_13358__$1 = temp__5823__auto___13357;
if(cljs.core.chunked_seq_QMARK_(seq__12363_13358__$1)){
var c__5673__auto___13359 = cljs.core.chunk_first(seq__12363_13358__$1);
var G__13360 = cljs.core.chunk_rest(seq__12363_13358__$1);
var G__13361 = c__5673__auto___13359;
var G__13362 = cljs.core.count(c__5673__auto___13359);
var G__13363 = (0);
seq__12363_13346 = G__13360;
chunk__12364_13347 = G__13361;
count__12365_13348 = G__13362;
i__12366_13349 = G__13363;
continue;
} else {
var node_13364 = cljs.core.first(seq__12363_13358__$1);
fragment.appendChild(shadow.dom._to_dom(node_13364));


var G__13365 = cljs.core.next(seq__12363_13358__$1);
var G__13366 = null;
var G__13367 = (0);
var G__13368 = (0);
seq__12363_13346 = G__13365;
chunk__12364_13347 = G__13366;
count__12365_13348 = G__13367;
i__12366_13349 = G__13368;
continue;
}
} else {
}
}
break;
}

return (new shadow.dom.NativeColl(fragment));
}));

(shadow.dom.fragment.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(shadow.dom.fragment.cljs$lang$applyTo = (function (seq12358){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq12358));
}));

/**
 * given a html string, eval all <script> tags and return the html without the scripts
 * don't do this for everything, only content you trust.
 */
shadow.dom.eval_scripts = (function shadow$dom$eval_scripts(s){
var scripts = cljs.core.re_seq(/<script[^>]*?>(.+?)<\/script>/,s);
var seq__12383_13371 = cljs.core.seq(scripts);
var chunk__12384_13372 = null;
var count__12385_13373 = (0);
var i__12386_13374 = (0);
while(true){
if((i__12386_13374 < count__12385_13373)){
var vec__12399_13375 = chunk__12384_13372.cljs$core$IIndexed$_nth$arity$2(null,i__12386_13374);
var script_tag_13376 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12399_13375,(0),null);
var script_body_13377 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12399_13375,(1),null);
eval(script_body_13377);


var G__13382 = seq__12383_13371;
var G__13383 = chunk__12384_13372;
var G__13384 = count__12385_13373;
var G__13385 = (i__12386_13374 + (1));
seq__12383_13371 = G__13382;
chunk__12384_13372 = G__13383;
count__12385_13373 = G__13384;
i__12386_13374 = G__13385;
continue;
} else {
var temp__5823__auto___13388 = cljs.core.seq(seq__12383_13371);
if(temp__5823__auto___13388){
var seq__12383_13389__$1 = temp__5823__auto___13388;
if(cljs.core.chunked_seq_QMARK_(seq__12383_13389__$1)){
var c__5673__auto___13390 = cljs.core.chunk_first(seq__12383_13389__$1);
var G__13391 = cljs.core.chunk_rest(seq__12383_13389__$1);
var G__13392 = c__5673__auto___13390;
var G__13393 = cljs.core.count(c__5673__auto___13390);
var G__13394 = (0);
seq__12383_13371 = G__13391;
chunk__12384_13372 = G__13392;
count__12385_13373 = G__13393;
i__12386_13374 = G__13394;
continue;
} else {
var vec__12404_13396 = cljs.core.first(seq__12383_13389__$1);
var script_tag_13397 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12404_13396,(0),null);
var script_body_13398 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12404_13396,(1),null);
eval(script_body_13398);


var G__13399 = cljs.core.next(seq__12383_13389__$1);
var G__13400 = null;
var G__13401 = (0);
var G__13402 = (0);
seq__12383_13371 = G__13399;
chunk__12384_13372 = G__13400;
count__12385_13373 = G__13401;
i__12386_13374 = G__13402;
continue;
}
} else {
}
}
break;
}

return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3((function (s__$1,p__12415){
var vec__12416 = p__12415;
var script_tag = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12416,(0),null);
var script_body = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12416,(1),null);
return clojure.string.replace(s__$1,script_tag,"");
}),s,scripts);
});
shadow.dom.str__GT_fragment = (function shadow$dom$str__GT_fragment(s){
var el = document.createElement("div");
(el.innerHTML = s);

return (new shadow.dom.NativeColl(goog.dom.childrenToNode_(document,el)));
});
shadow.dom.node_name = (function shadow$dom$node_name(el){
return shadow.dom.dom_node(el).nodeName;
});
shadow.dom.ancestor_by_class = (function shadow$dom$ancestor_by_class(el,cls){
return goog.dom.getAncestorByClass(shadow.dom.dom_node(el),cls);
});
shadow.dom.ancestor_by_tag = (function shadow$dom$ancestor_by_tag(var_args){
var G__12425 = arguments.length;
switch (G__12425) {
case 2:
return shadow.dom.ancestor_by_tag.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return shadow.dom.ancestor_by_tag.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.ancestor_by_tag.cljs$core$IFn$_invoke$arity$2 = (function (el,tag){
return goog.dom.getAncestorByTagNameAndClass(shadow.dom.dom_node(el),cljs.core.name(tag));
}));

(shadow.dom.ancestor_by_tag.cljs$core$IFn$_invoke$arity$3 = (function (el,tag,cls){
return goog.dom.getAncestorByTagNameAndClass(shadow.dom.dom_node(el),cljs.core.name(tag),cljs.core.name(cls));
}));

(shadow.dom.ancestor_by_tag.cljs$lang$maxFixedArity = 3);

shadow.dom.get_value = (function shadow$dom$get_value(dom){
return goog.dom.forms.getValue(shadow.dom.dom_node(dom));
});
shadow.dom.set_value = (function shadow$dom$set_value(dom,value){
return goog.dom.forms.setValue(shadow.dom.dom_node(dom),value);
});
shadow.dom.px = (function shadow$dom$px(value){
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1((value | 0))+"px");
});
shadow.dom.pct = (function shadow$dom$pct(value){
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(value)+"%");
});
shadow.dom.remove_style_STAR_ = (function shadow$dom$remove_style_STAR_(el,style){
return el.style.removeProperty(cljs.core.name(style));
});
shadow.dom.remove_style = (function shadow$dom$remove_style(el,style){
var el__$1 = shadow.dom.dom_node(el);
return shadow.dom.remove_style_STAR_(el__$1,style);
});
shadow.dom.remove_styles = (function shadow$dom$remove_styles(el,style_keys){
var el__$1 = shadow.dom.dom_node(el);
var seq__12430 = cljs.core.seq(style_keys);
var chunk__12431 = null;
var count__12432 = (0);
var i__12433 = (0);
while(true){
if((i__12433 < count__12432)){
var it = chunk__12431.cljs$core$IIndexed$_nth$arity$2(null,i__12433);
shadow.dom.remove_style_STAR_(el__$1,it);


var G__13499 = seq__12430;
var G__13500 = chunk__12431;
var G__13501 = count__12432;
var G__13502 = (i__12433 + (1));
seq__12430 = G__13499;
chunk__12431 = G__13500;
count__12432 = G__13501;
i__12433 = G__13502;
continue;
} else {
var temp__5823__auto__ = cljs.core.seq(seq__12430);
if(temp__5823__auto__){
var seq__12430__$1 = temp__5823__auto__;
if(cljs.core.chunked_seq_QMARK_(seq__12430__$1)){
var c__5673__auto__ = cljs.core.chunk_first(seq__12430__$1);
var G__13504 = cljs.core.chunk_rest(seq__12430__$1);
var G__13505 = c__5673__auto__;
var G__13506 = cljs.core.count(c__5673__auto__);
var G__13507 = (0);
seq__12430 = G__13504;
chunk__12431 = G__13505;
count__12432 = G__13506;
i__12433 = G__13507;
continue;
} else {
var it = cljs.core.first(seq__12430__$1);
shadow.dom.remove_style_STAR_(el__$1,it);


var G__13508 = cljs.core.next(seq__12430__$1);
var G__13509 = null;
var G__13510 = (0);
var G__13511 = (0);
seq__12430 = G__13508;
chunk__12431 = G__13509;
count__12432 = G__13510;
i__12433 = G__13511;
continue;
}
} else {
return null;
}
}
break;
}
});

/**
* @constructor
 * @implements {cljs.core.IRecord}
 * @implements {cljs.core.IKVReduce}
 * @implements {cljs.core.IEquiv}
 * @implements {cljs.core.IHash}
 * @implements {cljs.core.ICollection}
 * @implements {cljs.core.ICounted}
 * @implements {cljs.core.ISeqable}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.ICloneable}
 * @implements {cljs.core.IPrintWithWriter}
 * @implements {cljs.core.IIterable}
 * @implements {cljs.core.IWithMeta}
 * @implements {cljs.core.IAssociative}
 * @implements {cljs.core.IMap}
 * @implements {cljs.core.ILookup}
*/
shadow.dom.Coordinate = (function (x,y,__meta,__extmap,__hash){
this.x = x;
this.y = y;
this.__meta = __meta;
this.__extmap = __extmap;
this.__hash = __hash;
this.cljs$lang$protocol_mask$partition0$ = 2230716170;
this.cljs$lang$protocol_mask$partition1$ = 139264;
});
(shadow.dom.Coordinate.prototype.cljs$core$ILookup$_lookup$arity$2 = (function (this__5448__auto__,k__5449__auto__){
var self__ = this;
var this__5448__auto____$1 = this;
return this__5448__auto____$1.cljs$core$ILookup$_lookup$arity$3(null,k__5449__auto__,null);
}));

(shadow.dom.Coordinate.prototype.cljs$core$ILookup$_lookup$arity$3 = (function (this__5450__auto__,k12442,else__5451__auto__){
var self__ = this;
var this__5450__auto____$1 = this;
var G__12463 = k12442;
var G__12463__$1 = (((G__12463 instanceof cljs.core.Keyword))?G__12463.fqn:null);
switch (G__12463__$1) {
case "x":
return self__.x;

break;
case "y":
return self__.y;

break;
default:
return cljs.core.get.cljs$core$IFn$_invoke$arity$3(self__.__extmap,k12442,else__5451__auto__);

}
}));

(shadow.dom.Coordinate.prototype.cljs$core$IKVReduce$_kv_reduce$arity$3 = (function (this__5468__auto__,f__5469__auto__,init__5470__auto__){
var self__ = this;
var this__5468__auto____$1 = this;
return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3((function (ret__5471__auto__,p__12464){
var vec__12466 = p__12464;
var k__5472__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12466,(0),null);
var v__5473__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12466,(1),null);
return (f__5469__auto__.cljs$core$IFn$_invoke$arity$3 ? f__5469__auto__.cljs$core$IFn$_invoke$arity$3(ret__5471__auto__,k__5472__auto__,v__5473__auto__) : f__5469__auto__.call(null,ret__5471__auto__,k__5472__auto__,v__5473__auto__));
}),init__5470__auto__,this__5468__auto____$1);
}));

(shadow.dom.Coordinate.prototype.cljs$core$IPrintWithWriter$_pr_writer$arity$3 = (function (this__5463__auto__,writer__5464__auto__,opts__5465__auto__){
var self__ = this;
var this__5463__auto____$1 = this;
var pr_pair__5466__auto__ = (function (keyval__5467__auto__){
return cljs.core.pr_sequential_writer(writer__5464__auto__,cljs.core.pr_writer,""," ","",opts__5465__auto__,keyval__5467__auto__);
});
return cljs.core.pr_sequential_writer(writer__5464__auto__,pr_pair__5466__auto__,"#shadow.dom.Coordinate{",", ","}",opts__5465__auto__,cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[new cljs.core.Keyword(null,"x","x",2099068185),self__.x],null)),(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[new cljs.core.Keyword(null,"y","y",-1757859776),self__.y],null))], null),self__.__extmap));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IIterable$_iterator$arity$1 = (function (G__12441){
var self__ = this;
var G__12441__$1 = this;
return (new cljs.core.RecordIter((0),G__12441__$1,2,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"x","x",2099068185),new cljs.core.Keyword(null,"y","y",-1757859776)], null),(cljs.core.truth_(self__.__extmap)?cljs.core._iterator(self__.__extmap):cljs.core.nil_iter())));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IMeta$_meta$arity$1 = (function (this__5446__auto__){
var self__ = this;
var this__5446__auto____$1 = this;
return self__.__meta;
}));

(shadow.dom.Coordinate.prototype.cljs$core$ICloneable$_clone$arity$1 = (function (this__5443__auto__){
var self__ = this;
var this__5443__auto____$1 = this;
return (new shadow.dom.Coordinate(self__.x,self__.y,self__.__meta,self__.__extmap,self__.__hash));
}));

(shadow.dom.Coordinate.prototype.cljs$core$ICounted$_count$arity$1 = (function (this__5452__auto__){
var self__ = this;
var this__5452__auto____$1 = this;
return (2 + cljs.core.count(self__.__extmap));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IHash$_hash$arity$1 = (function (this__5444__auto__){
var self__ = this;
var this__5444__auto____$1 = this;
var h__5251__auto__ = self__.__hash;
if((!((h__5251__auto__ == null)))){
return h__5251__auto__;
} else {
var h__5251__auto____$1 = (function (coll__5445__auto__){
return (145542109 ^ cljs.core.hash_unordered_coll(coll__5445__auto__));
})(this__5444__auto____$1);
(self__.__hash = h__5251__auto____$1);

return h__5251__auto____$1;
}
}));

(shadow.dom.Coordinate.prototype.cljs$core$IEquiv$_equiv$arity$2 = (function (this12444,other12445){
var self__ = this;
var this12444__$1 = this;
return (((!((other12445 == null)))) && ((((this12444__$1.constructor === other12445.constructor)) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this12444__$1.x,other12445.x)) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this12444__$1.y,other12445.y)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this12444__$1.__extmap,other12445.__extmap)))))))));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IMap$_dissoc$arity$2 = (function (this__5458__auto__,k__5459__auto__){
var self__ = this;
var this__5458__auto____$1 = this;
if(cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"y","y",-1757859776),null,new cljs.core.Keyword(null,"x","x",2099068185),null], null), null),k__5459__auto__)){
return cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(cljs.core._with_meta(cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentArrayMap.EMPTY,this__5458__auto____$1),self__.__meta),k__5459__auto__);
} else {
return (new shadow.dom.Coordinate(self__.x,self__.y,self__.__meta,cljs.core.not_empty(cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(self__.__extmap,k__5459__auto__)),null));
}
}));

(shadow.dom.Coordinate.prototype.cljs$core$IAssociative$_contains_key_QMARK_$arity$2 = (function (this__5455__auto__,k12442){
var self__ = this;
var this__5455__auto____$1 = this;
var G__12480 = k12442;
var G__12480__$1 = (((G__12480 instanceof cljs.core.Keyword))?G__12480.fqn:null);
switch (G__12480__$1) {
case "x":
case "y":
return true;

break;
default:
return cljs.core.contains_QMARK_(self__.__extmap,k12442);

}
}));

(shadow.dom.Coordinate.prototype.cljs$core$IAssociative$_assoc$arity$3 = (function (this__5456__auto__,k__5457__auto__,G__12441){
var self__ = this;
var this__5456__auto____$1 = this;
var pred__12485 = cljs.core.keyword_identical_QMARK_;
var expr__12486 = k__5457__auto__;
if(cljs.core.truth_((pred__12485.cljs$core$IFn$_invoke$arity$2 ? pred__12485.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"x","x",2099068185),expr__12486) : pred__12485.call(null,new cljs.core.Keyword(null,"x","x",2099068185),expr__12486)))){
return (new shadow.dom.Coordinate(G__12441,self__.y,self__.__meta,self__.__extmap,null));
} else {
if(cljs.core.truth_((pred__12485.cljs$core$IFn$_invoke$arity$2 ? pred__12485.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"y","y",-1757859776),expr__12486) : pred__12485.call(null,new cljs.core.Keyword(null,"y","y",-1757859776),expr__12486)))){
return (new shadow.dom.Coordinate(self__.x,G__12441,self__.__meta,self__.__extmap,null));
} else {
return (new shadow.dom.Coordinate(self__.x,self__.y,self__.__meta,cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(self__.__extmap,k__5457__auto__,G__12441),null));
}
}
}));

(shadow.dom.Coordinate.prototype.cljs$core$ISeqable$_seq$arity$1 = (function (this__5461__auto__){
var self__ = this;
var this__5461__auto____$1 = this;
return cljs.core.seq(cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(new cljs.core.MapEntry(new cljs.core.Keyword(null,"x","x",2099068185),self__.x,null)),(new cljs.core.MapEntry(new cljs.core.Keyword(null,"y","y",-1757859776),self__.y,null))], null),self__.__extmap));
}));

(shadow.dom.Coordinate.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (this__5447__auto__,G__12441){
var self__ = this;
var this__5447__auto____$1 = this;
return (new shadow.dom.Coordinate(self__.x,self__.y,G__12441,self__.__extmap,self__.__hash));
}));

(shadow.dom.Coordinate.prototype.cljs$core$ICollection$_conj$arity$2 = (function (this__5453__auto__,entry__5454__auto__){
var self__ = this;
var this__5453__auto____$1 = this;
if(cljs.core.vector_QMARK_(entry__5454__auto__)){
return this__5453__auto____$1.cljs$core$IAssociative$_assoc$arity$3(null,cljs.core._nth(entry__5454__auto__,(0)),cljs.core._nth(entry__5454__auto__,(1)));
} else {
return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3(cljs.core._conj,this__5453__auto____$1,entry__5454__auto__);
}
}));

(shadow.dom.Coordinate.getBasis = (function (){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"x","x",-555367584,null),new cljs.core.Symbol(null,"y","y",-117328249,null)], null);
}));

(shadow.dom.Coordinate.cljs$lang$type = true);

(shadow.dom.Coordinate.cljs$lang$ctorPrSeq = (function (this__5494__auto__){
return (new cljs.core.List(null,"shadow.dom/Coordinate",null,(1),null));
}));

(shadow.dom.Coordinate.cljs$lang$ctorPrWriter = (function (this__5494__auto__,writer__5495__auto__){
return cljs.core._write(writer__5495__auto__,"shadow.dom/Coordinate");
}));

/**
 * Positional factory function for shadow.dom/Coordinate.
 */
shadow.dom.__GT_Coordinate = (function shadow$dom$__GT_Coordinate(x,y){
return (new shadow.dom.Coordinate(x,y,null,null,null));
});

/**
 * Factory function for shadow.dom/Coordinate, taking a map of keywords to field values.
 */
shadow.dom.map__GT_Coordinate = (function shadow$dom$map__GT_Coordinate(G__12449){
var extmap__5490__auto__ = (function (){var G__12504 = cljs.core.dissoc.cljs$core$IFn$_invoke$arity$variadic(G__12449,new cljs.core.Keyword(null,"x","x",2099068185),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"y","y",-1757859776)], 0));
if(cljs.core.record_QMARK_(G__12449)){
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentArrayMap.EMPTY,G__12504);
} else {
return G__12504;
}
})();
return (new shadow.dom.Coordinate(new cljs.core.Keyword(null,"x","x",2099068185).cljs$core$IFn$_invoke$arity$1(G__12449),new cljs.core.Keyword(null,"y","y",-1757859776).cljs$core$IFn$_invoke$arity$1(G__12449),null,cljs.core.not_empty(extmap__5490__auto__),null));
});

shadow.dom.get_position = (function shadow$dom$get_position(el){
var pos = goog.style.getPosition(shadow.dom.dom_node(el));
return shadow.dom.__GT_Coordinate(pos.x,pos.y);
});
shadow.dom.get_client_position = (function shadow$dom$get_client_position(el){
var pos = goog.style.getClientPosition(shadow.dom.dom_node(el));
return shadow.dom.__GT_Coordinate(pos.x,pos.y);
});
shadow.dom.get_page_offset = (function shadow$dom$get_page_offset(el){
var pos = goog.style.getPageOffset(shadow.dom.dom_node(el));
return shadow.dom.__GT_Coordinate(pos.x,pos.y);
});

/**
* @constructor
 * @implements {cljs.core.IRecord}
 * @implements {cljs.core.IKVReduce}
 * @implements {cljs.core.IEquiv}
 * @implements {cljs.core.IHash}
 * @implements {cljs.core.ICollection}
 * @implements {cljs.core.ICounted}
 * @implements {cljs.core.ISeqable}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.ICloneable}
 * @implements {cljs.core.IPrintWithWriter}
 * @implements {cljs.core.IIterable}
 * @implements {cljs.core.IWithMeta}
 * @implements {cljs.core.IAssociative}
 * @implements {cljs.core.IMap}
 * @implements {cljs.core.ILookup}
*/
shadow.dom.Size = (function (w,h,__meta,__extmap,__hash){
this.w = w;
this.h = h;
this.__meta = __meta;
this.__extmap = __extmap;
this.__hash = __hash;
this.cljs$lang$protocol_mask$partition0$ = 2230716170;
this.cljs$lang$protocol_mask$partition1$ = 139264;
});
(shadow.dom.Size.prototype.cljs$core$ILookup$_lookup$arity$2 = (function (this__5448__auto__,k__5449__auto__){
var self__ = this;
var this__5448__auto____$1 = this;
return this__5448__auto____$1.cljs$core$ILookup$_lookup$arity$3(null,k__5449__auto__,null);
}));

(shadow.dom.Size.prototype.cljs$core$ILookup$_lookup$arity$3 = (function (this__5450__auto__,k12512,else__5451__auto__){
var self__ = this;
var this__5450__auto____$1 = this;
var G__12523 = k12512;
var G__12523__$1 = (((G__12523 instanceof cljs.core.Keyword))?G__12523.fqn:null);
switch (G__12523__$1) {
case "w":
return self__.w;

break;
case "h":
return self__.h;

break;
default:
return cljs.core.get.cljs$core$IFn$_invoke$arity$3(self__.__extmap,k12512,else__5451__auto__);

}
}));

(shadow.dom.Size.prototype.cljs$core$IKVReduce$_kv_reduce$arity$3 = (function (this__5468__auto__,f__5469__auto__,init__5470__auto__){
var self__ = this;
var this__5468__auto____$1 = this;
return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3((function (ret__5471__auto__,p__12532){
var vec__12534 = p__12532;
var k__5472__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12534,(0),null);
var v__5473__auto__ = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12534,(1),null);
return (f__5469__auto__.cljs$core$IFn$_invoke$arity$3 ? f__5469__auto__.cljs$core$IFn$_invoke$arity$3(ret__5471__auto__,k__5472__auto__,v__5473__auto__) : f__5469__auto__.call(null,ret__5471__auto__,k__5472__auto__,v__5473__auto__));
}),init__5470__auto__,this__5468__auto____$1);
}));

(shadow.dom.Size.prototype.cljs$core$IPrintWithWriter$_pr_writer$arity$3 = (function (this__5463__auto__,writer__5464__auto__,opts__5465__auto__){
var self__ = this;
var this__5463__auto____$1 = this;
var pr_pair__5466__auto__ = (function (keyval__5467__auto__){
return cljs.core.pr_sequential_writer(writer__5464__auto__,cljs.core.pr_writer,""," ","",opts__5465__auto__,keyval__5467__auto__);
});
return cljs.core.pr_sequential_writer(writer__5464__auto__,pr_pair__5466__auto__,"#shadow.dom.Size{",", ","}",opts__5465__auto__,cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[new cljs.core.Keyword(null,"w","w",354169001),self__.w],null)),(new cljs.core.PersistentVector(null,2,(5),cljs.core.PersistentVector.EMPTY_NODE,[new cljs.core.Keyword(null,"h","h",1109658740),self__.h],null))], null),self__.__extmap));
}));

(shadow.dom.Size.prototype.cljs$core$IIterable$_iterator$arity$1 = (function (G__12511){
var self__ = this;
var G__12511__$1 = this;
return (new cljs.core.RecordIter((0),G__12511__$1,2,new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"w","w",354169001),new cljs.core.Keyword(null,"h","h",1109658740)], null),(cljs.core.truth_(self__.__extmap)?cljs.core._iterator(self__.__extmap):cljs.core.nil_iter())));
}));

(shadow.dom.Size.prototype.cljs$core$IMeta$_meta$arity$1 = (function (this__5446__auto__){
var self__ = this;
var this__5446__auto____$1 = this;
return self__.__meta;
}));

(shadow.dom.Size.prototype.cljs$core$ICloneable$_clone$arity$1 = (function (this__5443__auto__){
var self__ = this;
var this__5443__auto____$1 = this;
return (new shadow.dom.Size(self__.w,self__.h,self__.__meta,self__.__extmap,self__.__hash));
}));

(shadow.dom.Size.prototype.cljs$core$ICounted$_count$arity$1 = (function (this__5452__auto__){
var self__ = this;
var this__5452__auto____$1 = this;
return (2 + cljs.core.count(self__.__extmap));
}));

(shadow.dom.Size.prototype.cljs$core$IHash$_hash$arity$1 = (function (this__5444__auto__){
var self__ = this;
var this__5444__auto____$1 = this;
var h__5251__auto__ = self__.__hash;
if((!((h__5251__auto__ == null)))){
return h__5251__auto__;
} else {
var h__5251__auto____$1 = (function (coll__5445__auto__){
return (-1228019642 ^ cljs.core.hash_unordered_coll(coll__5445__auto__));
})(this__5444__auto____$1);
(self__.__hash = h__5251__auto____$1);

return h__5251__auto____$1;
}
}));

(shadow.dom.Size.prototype.cljs$core$IEquiv$_equiv$arity$2 = (function (this12513,other12514){
var self__ = this;
var this12513__$1 = this;
return (((!((other12514 == null)))) && ((((this12513__$1.constructor === other12514.constructor)) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this12513__$1.w,other12514.w)) && (((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this12513__$1.h,other12514.h)) && (cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(this12513__$1.__extmap,other12514.__extmap)))))))));
}));

(shadow.dom.Size.prototype.cljs$core$IMap$_dissoc$arity$2 = (function (this__5458__auto__,k__5459__auto__){
var self__ = this;
var this__5458__auto____$1 = this;
if(cljs.core.contains_QMARK_(new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"w","w",354169001),null,new cljs.core.Keyword(null,"h","h",1109658740),null], null), null),k__5459__auto__)){
return cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(cljs.core._with_meta(cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentArrayMap.EMPTY,this__5458__auto____$1),self__.__meta),k__5459__auto__);
} else {
return (new shadow.dom.Size(self__.w,self__.h,self__.__meta,cljs.core.not_empty(cljs.core.dissoc.cljs$core$IFn$_invoke$arity$2(self__.__extmap,k__5459__auto__)),null));
}
}));

(shadow.dom.Size.prototype.cljs$core$IAssociative$_contains_key_QMARK_$arity$2 = (function (this__5455__auto__,k12512){
var self__ = this;
var this__5455__auto____$1 = this;
var G__12553 = k12512;
var G__12553__$1 = (((G__12553 instanceof cljs.core.Keyword))?G__12553.fqn:null);
switch (G__12553__$1) {
case "w":
case "h":
return true;

break;
default:
return cljs.core.contains_QMARK_(self__.__extmap,k12512);

}
}));

(shadow.dom.Size.prototype.cljs$core$IAssociative$_assoc$arity$3 = (function (this__5456__auto__,k__5457__auto__,G__12511){
var self__ = this;
var this__5456__auto____$1 = this;
var pred__12557 = cljs.core.keyword_identical_QMARK_;
var expr__12558 = k__5457__auto__;
if(cljs.core.truth_((pred__12557.cljs$core$IFn$_invoke$arity$2 ? pred__12557.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"w","w",354169001),expr__12558) : pred__12557.call(null,new cljs.core.Keyword(null,"w","w",354169001),expr__12558)))){
return (new shadow.dom.Size(G__12511,self__.h,self__.__meta,self__.__extmap,null));
} else {
if(cljs.core.truth_((pred__12557.cljs$core$IFn$_invoke$arity$2 ? pred__12557.cljs$core$IFn$_invoke$arity$2(new cljs.core.Keyword(null,"h","h",1109658740),expr__12558) : pred__12557.call(null,new cljs.core.Keyword(null,"h","h",1109658740),expr__12558)))){
return (new shadow.dom.Size(self__.w,G__12511,self__.__meta,self__.__extmap,null));
} else {
return (new shadow.dom.Size(self__.w,self__.h,self__.__meta,cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(self__.__extmap,k__5457__auto__,G__12511),null));
}
}
}));

(shadow.dom.Size.prototype.cljs$core$ISeqable$_seq$arity$1 = (function (this__5461__auto__){
var self__ = this;
var this__5461__auto____$1 = this;
return cljs.core.seq(cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [(new cljs.core.MapEntry(new cljs.core.Keyword(null,"w","w",354169001),self__.w,null)),(new cljs.core.MapEntry(new cljs.core.Keyword(null,"h","h",1109658740),self__.h,null))], null),self__.__extmap));
}));

(shadow.dom.Size.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (this__5447__auto__,G__12511){
var self__ = this;
var this__5447__auto____$1 = this;
return (new shadow.dom.Size(self__.w,self__.h,G__12511,self__.__extmap,self__.__hash));
}));

(shadow.dom.Size.prototype.cljs$core$ICollection$_conj$arity$2 = (function (this__5453__auto__,entry__5454__auto__){
var self__ = this;
var this__5453__auto____$1 = this;
if(cljs.core.vector_QMARK_(entry__5454__auto__)){
return this__5453__auto____$1.cljs$core$IAssociative$_assoc$arity$3(null,cljs.core._nth(entry__5454__auto__,(0)),cljs.core._nth(entry__5454__auto__,(1)));
} else {
return cljs.core.reduce.cljs$core$IFn$_invoke$arity$3(cljs.core._conj,this__5453__auto____$1,entry__5454__auto__);
}
}));

(shadow.dom.Size.getBasis = (function (){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"w","w",1994700528,null),new cljs.core.Symbol(null,"h","h",-1544777029,null)], null);
}));

(shadow.dom.Size.cljs$lang$type = true);

(shadow.dom.Size.cljs$lang$ctorPrSeq = (function (this__5494__auto__){
return (new cljs.core.List(null,"shadow.dom/Size",null,(1),null));
}));

(shadow.dom.Size.cljs$lang$ctorPrWriter = (function (this__5494__auto__,writer__5495__auto__){
return cljs.core._write(writer__5495__auto__,"shadow.dom/Size");
}));

/**
 * Positional factory function for shadow.dom/Size.
 */
shadow.dom.__GT_Size = (function shadow$dom$__GT_Size(w,h){
return (new shadow.dom.Size(w,h,null,null,null));
});

/**
 * Factory function for shadow.dom/Size, taking a map of keywords to field values.
 */
shadow.dom.map__GT_Size = (function shadow$dom$map__GT_Size(G__12517){
var extmap__5490__auto__ = (function (){var G__12577 = cljs.core.dissoc.cljs$core$IFn$_invoke$arity$variadic(G__12517,new cljs.core.Keyword(null,"w","w",354169001),cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.Keyword(null,"h","h",1109658740)], 0));
if(cljs.core.record_QMARK_(G__12517)){
return cljs.core.into.cljs$core$IFn$_invoke$arity$2(cljs.core.PersistentArrayMap.EMPTY,G__12577);
} else {
return G__12577;
}
})();
return (new shadow.dom.Size(new cljs.core.Keyword(null,"w","w",354169001).cljs$core$IFn$_invoke$arity$1(G__12517),new cljs.core.Keyword(null,"h","h",1109658740).cljs$core$IFn$_invoke$arity$1(G__12517),null,cljs.core.not_empty(extmap__5490__auto__),null));
});

shadow.dom.size__GT_clj = (function shadow$dom$size__GT_clj(size){
return (new shadow.dom.Size(size.width,size.height,null,null,null));
});
shadow.dom.get_size = (function shadow$dom$get_size(el){
return shadow.dom.size__GT_clj(goog.style.getSize(shadow.dom.dom_node(el)));
});
shadow.dom.get_height = (function shadow$dom$get_height(el){
return shadow.dom.get_size(el).h;
});
shadow.dom.get_viewport_size = (function shadow$dom$get_viewport_size(){
return shadow.dom.size__GT_clj(goog.dom.getViewportSize());
});
shadow.dom.first_child = (function shadow$dom$first_child(el){
return (shadow.dom.dom_node(el).children[(0)]);
});
shadow.dom.select_option_values = (function shadow$dom$select_option_values(el){
var native$ = shadow.dom.dom_node(el);
var opts = (native$["options"]);
var a__5738__auto__ = opts;
var l__5739__auto__ = a__5738__auto__.length;
var i = (0);
var ret = cljs.core.PersistentVector.EMPTY;
while(true){
if((i < l__5739__auto__)){
var G__13706 = (i + (1));
var G__13707 = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(ret,(opts[i]["value"]));
i = G__13706;
ret = G__13707;
continue;
} else {
return ret;
}
break;
}
});
shadow.dom.build_url = (function shadow$dom$build_url(path,query_params){
if(cljs.core.empty_QMARK_(query_params)){
return path;
} else {
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(path)+"?"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(clojure.string.join.cljs$core$IFn$_invoke$arity$2("&",cljs.core.map.cljs$core$IFn$_invoke$arity$2((function (p__12606){
var vec__12607 = p__12606;
var k = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12607,(0),null);
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12607,(1),null);
return (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.name(k))+"="+cljs.core.str.cljs$core$IFn$_invoke$arity$1(encodeURIComponent((""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(v)))));
}),query_params))));
}
});
shadow.dom.redirect = (function shadow$dom$redirect(var_args){
var G__12615 = arguments.length;
switch (G__12615) {
case 1:
return shadow.dom.redirect.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return shadow.dom.redirect.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(shadow.dom.redirect.cljs$core$IFn$_invoke$arity$1 = (function (path){
return shadow.dom.redirect.cljs$core$IFn$_invoke$arity$2(path,cljs.core.PersistentArrayMap.EMPTY);
}));

(shadow.dom.redirect.cljs$core$IFn$_invoke$arity$2 = (function (path,query_params){
return (document["location"]["href"] = shadow.dom.build_url(path,query_params));
}));

(shadow.dom.redirect.cljs$lang$maxFixedArity = 2);

shadow.dom.reload_BANG_ = (function shadow$dom$reload_BANG_(){
return (document.location.href = document.location.href);
});
shadow.dom.tag_name = (function shadow$dom$tag_name(el){
var dom = shadow.dom.dom_node(el);
return dom.tagName;
});
shadow.dom.insert_after = (function shadow$dom$insert_after(ref,new$){
var new_node = shadow.dom.dom_node(new$);
goog.dom.insertSiblingAfter(new_node,shadow.dom.dom_node(ref));

return new_node;
});
shadow.dom.insert_before = (function shadow$dom$insert_before(ref,new$){
var new_node = shadow.dom.dom_node(new$);
goog.dom.insertSiblingBefore(new_node,shadow.dom.dom_node(ref));

return new_node;
});
shadow.dom.insert_first = (function shadow$dom$insert_first(ref,new$){
var temp__5821__auto__ = shadow.dom.dom_node(ref).firstChild;
if(cljs.core.truth_(temp__5821__auto__)){
var child = temp__5821__auto__;
return shadow.dom.insert_before(child,new$);
} else {
return shadow.dom.append.cljs$core$IFn$_invoke$arity$2(ref,new$);
}
});
shadow.dom.index_of = (function shadow$dom$index_of(el){
var el__$1 = shadow.dom.dom_node(el);
var i = (0);
while(true){
var ps = el__$1.previousSibling;
if((ps == null)){
return i;
} else {
var G__13736 = ps;
var G__13737 = (i + (1));
el__$1 = G__13736;
i = G__13737;
continue;
}
break;
}
});
shadow.dom.get_parent = (function shadow$dom$get_parent(el){
return goog.dom.getParentElement(shadow.dom.dom_node(el));
});
shadow.dom.parents = (function shadow$dom$parents(el){
var parent = shadow.dom.get_parent(el);
if(cljs.core.truth_(parent)){
return cljs.core.cons(parent,(new cljs.core.LazySeq(null,(function (){
return (shadow.dom.parents.cljs$core$IFn$_invoke$arity$1 ? shadow.dom.parents.cljs$core$IFn$_invoke$arity$1(parent) : shadow.dom.parents.call(null,parent));
}),null,null)));
} else {
return null;
}
});
shadow.dom.matches = (function shadow$dom$matches(el,sel){
return shadow.dom.dom_node(el).matches(sel);
});
shadow.dom.get_next_sibling = (function shadow$dom$get_next_sibling(el){
return goog.dom.getNextElementSibling(shadow.dom.dom_node(el));
});
shadow.dom.get_previous_sibling = (function shadow$dom$get_previous_sibling(el){
return goog.dom.getPreviousElementSibling(shadow.dom.dom_node(el));
});
shadow.dom.xmlns = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(new cljs.core.PersistentArrayMap(null, 2, ["svg","http://www.w3.org/2000/svg","xlink","http://www.w3.org/1999/xlink"], null));
shadow.dom.create_svg_node = (function shadow$dom$create_svg_node(tag_def,props){
var vec__12650 = shadow.dom.parse_tag(tag_def);
var tag_name = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12650,(0),null);
var tag_id = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12650,(1),null);
var tag_classes = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12650,(2),null);
var el = document.createElementNS("http://www.w3.org/2000/svg",tag_name);
if(cljs.core.truth_(tag_id)){
el.setAttribute("id",tag_id);
} else {
}

if(cljs.core.truth_(tag_classes)){
el.setAttribute("class",shadow.dom.merge_class_string(new cljs.core.Keyword(null,"class","class",-2030961996).cljs$core$IFn$_invoke$arity$1(props),tag_classes));
} else {
}

var seq__12655_13761 = cljs.core.seq(props);
var chunk__12656_13762 = null;
var count__12657_13763 = (0);
var i__12658_13764 = (0);
while(true){
if((i__12658_13764 < count__12657_13763)){
var vec__12680_13765 = chunk__12656_13762.cljs$core$IIndexed$_nth$arity$2(null,i__12658_13764);
var k_13766 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12680_13765,(0),null);
var v_13767 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12680_13765,(1),null);
el.setAttributeNS((function (){var temp__5823__auto__ = cljs.core.namespace(k_13766);
if(cljs.core.truth_(temp__5823__auto__)){
var ns = temp__5823__auto__;
return cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(shadow.dom.xmlns),ns);
} else {
return null;
}
})(),cljs.core.name(k_13766),v_13767);


var G__13769 = seq__12655_13761;
var G__13770 = chunk__12656_13762;
var G__13771 = count__12657_13763;
var G__13772 = (i__12658_13764 + (1));
seq__12655_13761 = G__13769;
chunk__12656_13762 = G__13770;
count__12657_13763 = G__13771;
i__12658_13764 = G__13772;
continue;
} else {
var temp__5823__auto___13773 = cljs.core.seq(seq__12655_13761);
if(temp__5823__auto___13773){
var seq__12655_13775__$1 = temp__5823__auto___13773;
if(cljs.core.chunked_seq_QMARK_(seq__12655_13775__$1)){
var c__5673__auto___13777 = cljs.core.chunk_first(seq__12655_13775__$1);
var G__13778 = cljs.core.chunk_rest(seq__12655_13775__$1);
var G__13779 = c__5673__auto___13777;
var G__13780 = cljs.core.count(c__5673__auto___13777);
var G__13781 = (0);
seq__12655_13761 = G__13778;
chunk__12656_13762 = G__13779;
count__12657_13763 = G__13780;
i__12658_13764 = G__13781;
continue;
} else {
var vec__12694_13783 = cljs.core.first(seq__12655_13775__$1);
var k_13784 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12694_13783,(0),null);
var v_13785 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12694_13783,(1),null);
el.setAttributeNS((function (){var temp__5823__auto____$1 = cljs.core.namespace(k_13784);
if(cljs.core.truth_(temp__5823__auto____$1)){
var ns = temp__5823__auto____$1;
return cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(shadow.dom.xmlns),ns);
} else {
return null;
}
})(),cljs.core.name(k_13784),v_13785);


var G__13790 = cljs.core.next(seq__12655_13775__$1);
var G__13791 = null;
var G__13792 = (0);
var G__13793 = (0);
seq__12655_13761 = G__13790;
chunk__12656_13762 = G__13791;
count__12657_13763 = G__13792;
i__12658_13764 = G__13793;
continue;
}
} else {
}
}
break;
}

return el;
});
shadow.dom.svg_node = (function shadow$dom$svg_node(el){
if((el == null)){
return null;
} else {
if((((!((el == null))))?((((false) || ((cljs.core.PROTOCOL_SENTINEL === el.shadow$dom$SVGElement$))))?true:false):false)){
return el.shadow$dom$SVGElement$_to_svg$arity$1(null);
} else {
return el;

}
}
});
shadow.dom.make_svg_node = (function shadow$dom$make_svg_node(structure){
var vec__12706 = shadow.dom.destructure_node(shadow.dom.create_svg_node,structure);
var node = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12706,(0),null);
var node_children = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__12706,(1),null);
var seq__12710_13805 = cljs.core.seq(node_children);
var chunk__12712_13806 = null;
var count__12713_13807 = (0);
var i__12714_13808 = (0);
while(true){
if((i__12714_13808 < count__12713_13807)){
var child_struct_13815 = chunk__12712_13806.cljs$core$IIndexed$_nth$arity$2(null,i__12714_13808);
if((!((child_struct_13815 == null)))){
if(typeof child_struct_13815 === 'string'){
var text_13818 = (node["textContent"]);
(node["textContent"] = (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(text_13818)+cljs.core.str.cljs$core$IFn$_invoke$arity$1(child_struct_13815)));
} else {
var children_13819 = shadow.dom.svg_node(child_struct_13815);
if(cljs.core.seq_QMARK_(children_13819)){
var seq__12753_13822 = cljs.core.seq(children_13819);
var chunk__12755_13823 = null;
var count__12756_13824 = (0);
var i__12757_13825 = (0);
while(true){
if((i__12757_13825 < count__12756_13824)){
var child_13826 = chunk__12755_13823.cljs$core$IIndexed$_nth$arity$2(null,i__12757_13825);
if(cljs.core.truth_(child_13826)){
node.appendChild(child_13826);


var G__13828 = seq__12753_13822;
var G__13829 = chunk__12755_13823;
var G__13830 = count__12756_13824;
var G__13831 = (i__12757_13825 + (1));
seq__12753_13822 = G__13828;
chunk__12755_13823 = G__13829;
count__12756_13824 = G__13830;
i__12757_13825 = G__13831;
continue;
} else {
var G__13834 = seq__12753_13822;
var G__13835 = chunk__12755_13823;
var G__13836 = count__12756_13824;
var G__13837 = (i__12757_13825 + (1));
seq__12753_13822 = G__13834;
chunk__12755_13823 = G__13835;
count__12756_13824 = G__13836;
i__12757_13825 = G__13837;
continue;
}
} else {
var temp__5823__auto___13839 = cljs.core.seq(seq__12753_13822);
if(temp__5823__auto___13839){
var seq__12753_13840__$1 = temp__5823__auto___13839;
if(cljs.core.chunked_seq_QMARK_(seq__12753_13840__$1)){
var c__5673__auto___13841 = cljs.core.chunk_first(seq__12753_13840__$1);
var G__13842 = cljs.core.chunk_rest(seq__12753_13840__$1);
var G__13843 = c__5673__auto___13841;
var G__13844 = cljs.core.count(c__5673__auto___13841);
var G__13845 = (0);
seq__12753_13822 = G__13842;
chunk__12755_13823 = G__13843;
count__12756_13824 = G__13844;
i__12757_13825 = G__13845;
continue;
} else {
var child_13847 = cljs.core.first(seq__12753_13840__$1);
if(cljs.core.truth_(child_13847)){
node.appendChild(child_13847);


var G__13848 = cljs.core.next(seq__12753_13840__$1);
var G__13849 = null;
var G__13850 = (0);
var G__13851 = (0);
seq__12753_13822 = G__13848;
chunk__12755_13823 = G__13849;
count__12756_13824 = G__13850;
i__12757_13825 = G__13851;
continue;
} else {
var G__13854 = cljs.core.next(seq__12753_13840__$1);
var G__13855 = null;
var G__13856 = (0);
var G__13857 = (0);
seq__12753_13822 = G__13854;
chunk__12755_13823 = G__13855;
count__12756_13824 = G__13856;
i__12757_13825 = G__13857;
continue;
}
}
} else {
}
}
break;
}
} else {
node.appendChild(children_13819);
}
}


var G__13858 = seq__12710_13805;
var G__13859 = chunk__12712_13806;
var G__13860 = count__12713_13807;
var G__13861 = (i__12714_13808 + (1));
seq__12710_13805 = G__13858;
chunk__12712_13806 = G__13859;
count__12713_13807 = G__13860;
i__12714_13808 = G__13861;
continue;
} else {
var G__13863 = seq__12710_13805;
var G__13864 = chunk__12712_13806;
var G__13865 = count__12713_13807;
var G__13866 = (i__12714_13808 + (1));
seq__12710_13805 = G__13863;
chunk__12712_13806 = G__13864;
count__12713_13807 = G__13865;
i__12714_13808 = G__13866;
continue;
}
} else {
var temp__5823__auto___13867 = cljs.core.seq(seq__12710_13805);
if(temp__5823__auto___13867){
var seq__12710_13868__$1 = temp__5823__auto___13867;
if(cljs.core.chunked_seq_QMARK_(seq__12710_13868__$1)){
var c__5673__auto___13870 = cljs.core.chunk_first(seq__12710_13868__$1);
var G__13871 = cljs.core.chunk_rest(seq__12710_13868__$1);
var G__13872 = c__5673__auto___13870;
var G__13873 = cljs.core.count(c__5673__auto___13870);
var G__13874 = (0);
seq__12710_13805 = G__13871;
chunk__12712_13806 = G__13872;
count__12713_13807 = G__13873;
i__12714_13808 = G__13874;
continue;
} else {
var child_struct_13875 = cljs.core.first(seq__12710_13868__$1);
if((!((child_struct_13875 == null)))){
if(typeof child_struct_13875 === 'string'){
var text_13878 = (node["textContent"]);
(node["textContent"] = (""+cljs.core.str.cljs$core$IFn$_invoke$arity$1(text_13878)+cljs.core.str.cljs$core$IFn$_invoke$arity$1(child_struct_13875)));
} else {
var children_13880 = shadow.dom.svg_node(child_struct_13875);
if(cljs.core.seq_QMARK_(children_13880)){
var seq__12776_13881 = cljs.core.seq(children_13880);
var chunk__12778_13882 = null;
var count__12779_13883 = (0);
var i__12780_13884 = (0);
while(true){
if((i__12780_13884 < count__12779_13883)){
var child_13885 = chunk__12778_13882.cljs$core$IIndexed$_nth$arity$2(null,i__12780_13884);
if(cljs.core.truth_(child_13885)){
node.appendChild(child_13885);


var G__13887 = seq__12776_13881;
var G__13888 = chunk__12778_13882;
var G__13889 = count__12779_13883;
var G__13890 = (i__12780_13884 + (1));
seq__12776_13881 = G__13887;
chunk__12778_13882 = G__13888;
count__12779_13883 = G__13889;
i__12780_13884 = G__13890;
continue;
} else {
var G__13892 = seq__12776_13881;
var G__13893 = chunk__12778_13882;
var G__13894 = count__12779_13883;
var G__13895 = (i__12780_13884 + (1));
seq__12776_13881 = G__13892;
chunk__12778_13882 = G__13893;
count__12779_13883 = G__13894;
i__12780_13884 = G__13895;
continue;
}
} else {
var temp__5823__auto___13896__$1 = cljs.core.seq(seq__12776_13881);
if(temp__5823__auto___13896__$1){
var seq__12776_13897__$1 = temp__5823__auto___13896__$1;
if(cljs.core.chunked_seq_QMARK_(seq__12776_13897__$1)){
var c__5673__auto___13900 = cljs.core.chunk_first(seq__12776_13897__$1);
var G__13901 = cljs.core.chunk_rest(seq__12776_13897__$1);
var G__13902 = c__5673__auto___13900;
var G__13903 = cljs.core.count(c__5673__auto___13900);
var G__13904 = (0);
seq__12776_13881 = G__13901;
chunk__12778_13882 = G__13902;
count__12779_13883 = G__13903;
i__12780_13884 = G__13904;
continue;
} else {
var child_13908 = cljs.core.first(seq__12776_13897__$1);
if(cljs.core.truth_(child_13908)){
node.appendChild(child_13908);


var G__13910 = cljs.core.next(seq__12776_13897__$1);
var G__13911 = null;
var G__13912 = (0);
var G__13913 = (0);
seq__12776_13881 = G__13910;
chunk__12778_13882 = G__13911;
count__12779_13883 = G__13912;
i__12780_13884 = G__13913;
continue;
} else {
var G__13915 = cljs.core.next(seq__12776_13897__$1);
var G__13916 = null;
var G__13917 = (0);
var G__13918 = (0);
seq__12776_13881 = G__13915;
chunk__12778_13882 = G__13916;
count__12779_13883 = G__13917;
i__12780_13884 = G__13918;
continue;
}
}
} else {
}
}
break;
}
} else {
node.appendChild(children_13880);
}
}


var G__13919 = cljs.core.next(seq__12710_13868__$1);
var G__13920 = null;
var G__13921 = (0);
var G__13922 = (0);
seq__12710_13805 = G__13919;
chunk__12712_13806 = G__13920;
count__12713_13807 = G__13921;
i__12714_13808 = G__13922;
continue;
} else {
var G__13924 = cljs.core.next(seq__12710_13868__$1);
var G__13925 = null;
var G__13926 = (0);
var G__13927 = (0);
seq__12710_13805 = G__13924;
chunk__12712_13806 = G__13925;
count__12713_13807 = G__13926;
i__12714_13808 = G__13927;
continue;
}
}
} else {
}
}
break;
}

return node;
});
(shadow.dom.SVGElement["string"] = true);

(shadow.dom._to_svg["string"] = (function (this$){
if((this$ instanceof cljs.core.Keyword)){
return shadow.dom.make_svg_node(new cljs.core.PersistentVector(null, 1, 5, cljs.core.PersistentVector.EMPTY_NODE, [this$], null));
} else {
throw cljs.core.ex_info.cljs$core$IFn$_invoke$arity$2("strings cannot be in svgs",new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"this","this",-611633625),this$], null));
}
}));

(cljs.core.PersistentVector.prototype.shadow$dom$SVGElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.PersistentVector.prototype.shadow$dom$SVGElement$_to_svg$arity$1 = (function (this$){
var this$__$1 = this;
return shadow.dom.make_svg_node(this$__$1);
}));

(cljs.core.LazySeq.prototype.shadow$dom$SVGElement$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.LazySeq.prototype.shadow$dom$SVGElement$_to_svg$arity$1 = (function (this$){
var this$__$1 = this;
return cljs.core.map.cljs$core$IFn$_invoke$arity$2(shadow.dom._to_svg,this$__$1);
}));

(shadow.dom.SVGElement["null"] = true);

(shadow.dom._to_svg["null"] = (function (_){
return null;
}));
shadow.dom.svg = (function shadow$dom$svg(var_args){
var args__5882__auto__ = [];
var len__5876__auto___13933 = arguments.length;
var i__5877__auto___13934 = (0);
while(true){
if((i__5877__auto___13934 < len__5876__auto___13933)){
args__5882__auto__.push((arguments[i__5877__auto___13934]));

var G__13935 = (i__5877__auto___13934 + (1));
i__5877__auto___13934 = G__13935;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((1) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((1)),(0),null)):null);
return shadow.dom.svg.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),argseq__5883__auto__);
});

(shadow.dom.svg.cljs$core$IFn$_invoke$arity$variadic = (function (attrs,children){
return shadow.dom._to_svg(cljs.core.vec(cljs.core.concat.cljs$core$IFn$_invoke$arity$2(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"svg","svg",856789142),attrs], null),children)));
}));

(shadow.dom.svg.cljs$lang$maxFixedArity = (1));

/** @this {Function} */
(shadow.dom.svg.cljs$lang$applyTo = (function (seq12801){
var G__12802 = cljs.core.first(seq12801);
var seq12801__$1 = cljs.core.next(seq12801);
var self__5861__auto__ = this;
return self__5861__auto__.cljs$core$IFn$_invoke$arity$variadic(G__12802,seq12801__$1);
}));


//# sourceMappingURL=shadow.dom.js.map
