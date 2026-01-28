goog.provide('cljs.core.async');
goog.scope(function(){
  cljs.core.async.goog$module$goog$array = goog.module.get('goog.array');
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Handler}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async14666 = (function (f,blockable,meta14667){
this.f = f;
this.blockable = blockable;
this.meta14667 = meta14667;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async14666.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_14668,meta14667__$1){
var self__ = this;
var _14668__$1 = this;
return (new cljs.core.async.t_cljs$core$async14666(self__.f,self__.blockable,meta14667__$1));
}));

(cljs.core.async.t_cljs$core$async14666.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_14668){
var self__ = this;
var _14668__$1 = this;
return self__.meta14667;
}));

(cljs.core.async.t_cljs$core$async14666.prototype.cljs$core$async$impl$protocols$Handler$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async14666.prototype.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return true;
}));

(cljs.core.async.t_cljs$core$async14666.prototype.cljs$core$async$impl$protocols$Handler$blockable_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.blockable;
}));

(cljs.core.async.t_cljs$core$async14666.prototype.cljs$core$async$impl$protocols$Handler$commit$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.f;
}));

(cljs.core.async.t_cljs$core$async14666.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),new cljs.core.Symbol(null,"blockable","blockable",-28395259,null),new cljs.core.Symbol(null,"meta14667","meta14667",-1371439747,null)], null);
}));

(cljs.core.async.t_cljs$core$async14666.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async14666.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async14666");

(cljs.core.async.t_cljs$core$async14666.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async14666");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async14666.
 */
cljs.core.async.__GT_t_cljs$core$async14666 = (function cljs$core$async$__GT_t_cljs$core$async14666(f,blockable,meta14667){
return (new cljs.core.async.t_cljs$core$async14666(f,blockable,meta14667));
});


cljs.core.async.fn_handler = (function cljs$core$async$fn_handler(var_args){
var G__14650 = arguments.length;
switch (G__14650) {
case 1:
return cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1 = (function (f){
return cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2(f,true);
}));

(cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2 = (function (f,blockable){
return (new cljs.core.async.t_cljs$core$async14666(f,blockable,cljs.core.PersistentArrayMap.EMPTY));
}));

(cljs.core.async.fn_handler.cljs$lang$maxFixedArity = 2);

/**
 * Returns a fixed buffer of size n. When full, puts will block/park.
 */
cljs.core.async.buffer = (function cljs$core$async$buffer(n){
return cljs.core.async.impl.buffers.fixed_buffer(n);
});
/**
 * Returns a buffer of size n. When full, puts will complete but
 *   val will be dropped (no transfer).
 */
cljs.core.async.dropping_buffer = (function cljs$core$async$dropping_buffer(n){
return cljs.core.async.impl.buffers.dropping_buffer(n);
});
/**
 * Returns a buffer of size n. When full, puts will complete, and be
 *   buffered, but oldest elements in buffer will be dropped (not
 *   transferred).
 */
cljs.core.async.sliding_buffer = (function cljs$core$async$sliding_buffer(n){
return cljs.core.async.impl.buffers.sliding_buffer(n);
});
/**
 * Returns true if a channel created with buff will never block. That is to say,
 * puts into this buffer will never cause the buffer to be full. 
 */
cljs.core.async.unblocking_buffer_QMARK_ = (function cljs$core$async$unblocking_buffer_QMARK_(buff){
if((!((buff == null)))){
if(((false) || ((cljs.core.PROTOCOL_SENTINEL === buff.cljs$core$async$impl$protocols$UnblockingBuffer$)))){
return true;
} else {
if((!buff.cljs$lang$protocol_mask$partition$)){
return cljs.core.native_satisfies_QMARK_(cljs.core.async.impl.protocols.UnblockingBuffer,buff);
} else {
return false;
}
}
} else {
return cljs.core.native_satisfies_QMARK_(cljs.core.async.impl.protocols.UnblockingBuffer,buff);
}
});
/**
 * Creates a channel with an optional buffer, an optional transducer (like (map f),
 *   (filter p) etc or a composition thereof), and an optional exception handler.
 *   If buf-or-n is a number, will create and use a fixed buffer of that size. If a
 *   transducer is supplied a buffer must be specified. ex-handler must be a
 *   fn of one argument - if an exception occurs during transformation it will be called
 *   with the thrown value as an argument, and any non-nil return value will be placed
 *   in the channel.
 */
cljs.core.async.chan = (function cljs$core$async$chan(var_args){
var G__14752 = arguments.length;
switch (G__14752) {
case 0:
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$0();

break;
case 1:
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$0 = (function (){
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(null);
}));

(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1 = (function (buf_or_n){
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3(buf_or_n,null,null);
}));

(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$2 = (function (buf_or_n,xform){
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3(buf_or_n,xform,null);
}));

(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3 = (function (buf_or_n,xform,ex_handler){
var buf_or_n__$1 = ((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(buf_or_n,(0)))?null:buf_or_n);
if(cljs.core.truth_(xform)){
if(cljs.core.truth_(buf_or_n__$1)){
} else {
throw (new Error((""+"Assert failed: "+"buffer must be supplied when transducer is"+"\n"+"buf-or-n")));
}
} else {
}

return cljs.core.async.impl.channels.chan.cljs$core$IFn$_invoke$arity$3(((typeof buf_or_n__$1 === 'number')?cljs.core.async.buffer(buf_or_n__$1):buf_or_n__$1),xform,ex_handler);
}));

(cljs.core.async.chan.cljs$lang$maxFixedArity = 3);

/**
 * Creates a promise channel with an optional transducer, and an optional
 *   exception-handler. A promise channel can take exactly one value that consumers
 *   will receive. Once full, puts complete but val is dropped (no transfer).
 *   Consumers will block until either a value is placed in the channel or the
 *   channel is closed, then return the value (or nil) forever. See chan for the
 *   semantics of xform and ex-handler.
 */
cljs.core.async.promise_chan = (function cljs$core$async$promise_chan(var_args){
var G__14768 = arguments.length;
switch (G__14768) {
case 0:
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$0();

break;
case 1:
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$0 = (function (){
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$1(null);
}));

(cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$1 = (function (xform){
return cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$2(xform,null);
}));

(cljs.core.async.promise_chan.cljs$core$IFn$_invoke$arity$2 = (function (xform,ex_handler){
return cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3(cljs.core.async.impl.buffers.promise_buffer(),xform,ex_handler);
}));

(cljs.core.async.promise_chan.cljs$lang$maxFixedArity = 2);

/**
 * Returns a channel that will close after msecs
 */
cljs.core.async.timeout = (function cljs$core$async$timeout(msecs){
return cljs.core.async.impl.timers.timeout(msecs);
});
/**
 * takes a val from port. Must be called inside a (go ...) block. Will
 *   return nil if closed. Will park if nothing is available.
 *   Returns true unless port is already closed
 */
cljs.core.async._LT__BANG_ = (function cljs$core$async$_LT__BANG_(port){
throw (new Error("<! used not in (go ...) block"));
});
/**
 * Asynchronously takes a val from port, passing to fn1. Will pass nil
 * if closed. If on-caller? (default true) is true, and value is
 * immediately available, will call fn1 on calling thread.
 * Returns nil.
 */
cljs.core.async.take_BANG_ = (function cljs$core$async$take_BANG_(var_args){
var G__14808 = arguments.length;
switch (G__14808) {
case 2:
return cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$2 = (function (port,fn1){
return cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$3(port,fn1,true);
}));

(cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$3 = (function (port,fn1,on_caller_QMARK_){
var ret = cljs.core.async.impl.protocols.take_BANG_(port,cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1(fn1));
if(cljs.core.truth_(ret)){
var val_17957 = cljs.core.deref(ret);
if(cljs.core.truth_(on_caller_QMARK_)){
(fn1.cljs$core$IFn$_invoke$arity$1 ? fn1.cljs$core$IFn$_invoke$arity$1(val_17957) : fn1.call(null,val_17957));
} else {
cljs.core.async.impl.dispatch.run((function (){
return (fn1.cljs$core$IFn$_invoke$arity$1 ? fn1.cljs$core$IFn$_invoke$arity$1(val_17957) : fn1.call(null,val_17957));
}));
}
} else {
}

return null;
}));

(cljs.core.async.take_BANG_.cljs$lang$maxFixedArity = 3);

cljs.core.async.nop = (function cljs$core$async$nop(_){
return null;
});
cljs.core.async.fhnop = cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1(cljs.core.async.nop);
/**
 * puts a val into port. nil values are not allowed. Must be called
 *   inside a (go ...) block. Will park if no buffer space is available.
 *   Returns true unless port is already closed.
 */
cljs.core.async._GT__BANG_ = (function cljs$core$async$_GT__BANG_(port,val){
throw (new Error(">! used not in (go ...) block"));
});
/**
 * Asynchronously puts a val into port, calling fn1 (if supplied) when
 * complete. nil values are not allowed. Will throw if closed. If
 * on-caller? (default true) is true, and the put is immediately
 * accepted, will call fn1 on calling thread.  Returns nil.
 */
cljs.core.async.put_BANG_ = (function cljs$core$async$put_BANG_(var_args){
var G__14850 = arguments.length;
switch (G__14850) {
case 2:
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
case 4:
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2 = (function (port,val){
var temp__5821__auto__ = cljs.core.async.impl.protocols.put_BANG_(port,val,cljs.core.async.fhnop);
if(cljs.core.truth_(temp__5821__auto__)){
var ret = temp__5821__auto__;
return cljs.core.deref(ret);
} else {
return true;
}
}));

(cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$3 = (function (port,val,fn1){
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$4(port,val,fn1,true);
}));

(cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$4 = (function (port,val,fn1,on_caller_QMARK_){
var temp__5821__auto__ = cljs.core.async.impl.protocols.put_BANG_(port,val,cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$1(fn1));
if(cljs.core.truth_(temp__5821__auto__)){
var retb = temp__5821__auto__;
var ret = cljs.core.deref(retb);
if(cljs.core.truth_(on_caller_QMARK_)){
(fn1.cljs$core$IFn$_invoke$arity$1 ? fn1.cljs$core$IFn$_invoke$arity$1(ret) : fn1.call(null,ret));
} else {
cljs.core.async.impl.dispatch.run((function (){
return (fn1.cljs$core$IFn$_invoke$arity$1 ? fn1.cljs$core$IFn$_invoke$arity$1(ret) : fn1.call(null,ret));
}));
}

return ret;
} else {
return true;
}
}));

(cljs.core.async.put_BANG_.cljs$lang$maxFixedArity = 4);

cljs.core.async.close_BANG_ = (function cljs$core$async$close_BANG_(port){
return cljs.core.async.impl.protocols.close_BANG_(port);
});
cljs.core.async.random_array = (function cljs$core$async$random_array(n){
var a = (new Array(n));
var n__5741__auto___17964 = n;
var x_17965 = (0);
while(true){
if((x_17965 < n__5741__auto___17964)){
(a[x_17965] = x_17965);

var G__17966 = (x_17965 + (1));
x_17965 = G__17966;
continue;
} else {
}
break;
}

cljs.core.async.goog$module$goog$array.shuffle(a);

return a;
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Handler}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async14881 = (function (flag,meta14882){
this.flag = flag;
this.meta14882 = meta14882;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async14881.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_14883,meta14882__$1){
var self__ = this;
var _14883__$1 = this;
return (new cljs.core.async.t_cljs$core$async14881(self__.flag,meta14882__$1));
}));

(cljs.core.async.t_cljs$core$async14881.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_14883){
var self__ = this;
var _14883__$1 = this;
return self__.meta14882;
}));

(cljs.core.async.t_cljs$core$async14881.prototype.cljs$core$async$impl$protocols$Handler$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async14881.prototype.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.deref(self__.flag);
}));

(cljs.core.async.t_cljs$core$async14881.prototype.cljs$core$async$impl$protocols$Handler$blockable_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return true;
}));

(cljs.core.async.t_cljs$core$async14881.prototype.cljs$core$async$impl$protocols$Handler$commit$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
cljs.core.reset_BANG_(self__.flag,null);

return true;
}));

(cljs.core.async.t_cljs$core$async14881.getBasis = (function (){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"flag","flag",-1565787888,null),new cljs.core.Symbol(null,"meta14882","meta14882",-495133664,null)], null);
}));

(cljs.core.async.t_cljs$core$async14881.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async14881.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async14881");

(cljs.core.async.t_cljs$core$async14881.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async14881");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async14881.
 */
cljs.core.async.__GT_t_cljs$core$async14881 = (function cljs$core$async$__GT_t_cljs$core$async14881(flag,meta14882){
return (new cljs.core.async.t_cljs$core$async14881(flag,meta14882));
});


cljs.core.async.alt_flag = (function cljs$core$async$alt_flag(){
var flag = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(true);
return (new cljs.core.async.t_cljs$core$async14881(flag,cljs.core.PersistentArrayMap.EMPTY));
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Handler}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async14934 = (function (flag,cb,meta14935){
this.flag = flag;
this.cb = cb;
this.meta14935 = meta14935;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async14934.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_14936,meta14935__$1){
var self__ = this;
var _14936__$1 = this;
return (new cljs.core.async.t_cljs$core$async14934(self__.flag,self__.cb,meta14935__$1));
}));

(cljs.core.async.t_cljs$core$async14934.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_14936){
var self__ = this;
var _14936__$1 = this;
return self__.meta14935;
}));

(cljs.core.async.t_cljs$core$async14934.prototype.cljs$core$async$impl$protocols$Handler$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async14934.prototype.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.active_QMARK_(self__.flag);
}));

(cljs.core.async.t_cljs$core$async14934.prototype.cljs$core$async$impl$protocols$Handler$blockable_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return true;
}));

(cljs.core.async.t_cljs$core$async14934.prototype.cljs$core$async$impl$protocols$Handler$commit$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
cljs.core.async.impl.protocols.commit(self__.flag);

return self__.cb;
}));

(cljs.core.async.t_cljs$core$async14934.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"flag","flag",-1565787888,null),new cljs.core.Symbol(null,"cb","cb",-2064487928,null),new cljs.core.Symbol(null,"meta14935","meta14935",1879360794,null)], null);
}));

(cljs.core.async.t_cljs$core$async14934.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async14934.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async14934");

(cljs.core.async.t_cljs$core$async14934.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async14934");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async14934.
 */
cljs.core.async.__GT_t_cljs$core$async14934 = (function cljs$core$async$__GT_t_cljs$core$async14934(flag,cb,meta14935){
return (new cljs.core.async.t_cljs$core$async14934(flag,cb,meta14935));
});


cljs.core.async.alt_handler = (function cljs$core$async$alt_handler(flag,cb){
return (new cljs.core.async.t_cljs$core$async14934(flag,cb,cljs.core.PersistentArrayMap.EMPTY));
});
/**
 * returns derefable [val port] if immediate, nil if enqueued
 */
cljs.core.async.do_alts = (function cljs$core$async$do_alts(fret,ports,opts){
if((cljs.core.count(ports) > (0))){
} else {
throw (new Error((""+"Assert failed: "+"alts must have at least one channel operation"+"\n"+"(pos? (count ports))")));
}

var flag = cljs.core.async.alt_flag();
var ports__$1 = cljs.core.vec(ports);
var n = cljs.core.count(ports__$1);
var _ = (function (){var i = (0);
while(true){
if((i < n)){
var port_17971 = cljs.core.nth.cljs$core$IFn$_invoke$arity$2(ports__$1,i);
if(cljs.core.vector_QMARK_(port_17971)){
if((!(((port_17971.cljs$core$IFn$_invoke$arity$1 ? port_17971.cljs$core$IFn$_invoke$arity$1((1)) : port_17971.call(null,(1))) == null)))){
} else {
throw (new Error((""+"Assert failed: "+"can't put nil on channel"+"\n"+"(some? (port 1))")));
}
} else {
}

var G__17972 = (i + (1));
i = G__17972;
continue;
} else {
return null;
}
break;
}
})();
var idxs = cljs.core.async.random_array(n);
var priority = new cljs.core.Keyword(null,"priority","priority",1431093715).cljs$core$IFn$_invoke$arity$1(opts);
var ret = (function (){var i = (0);
while(true){
if((i < n)){
var idx = (cljs.core.truth_(priority)?i:(idxs[i]));
var port = cljs.core.nth.cljs$core$IFn$_invoke$arity$2(ports__$1,idx);
var wport = ((cljs.core.vector_QMARK_(port))?(port.cljs$core$IFn$_invoke$arity$1 ? port.cljs$core$IFn$_invoke$arity$1((0)) : port.call(null,(0))):null);
var vbox = (cljs.core.truth_(wport)?(function (){var val = (port.cljs$core$IFn$_invoke$arity$1 ? port.cljs$core$IFn$_invoke$arity$1((1)) : port.call(null,(1)));
return cljs.core.async.impl.protocols.put_BANG_(wport,val,cljs.core.async.alt_handler(flag,((function (i,val,idx,port,wport,flag,ports__$1,n,_,idxs,priority){
return (function (p1__14956_SHARP_){
var G__14961 = new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [p1__14956_SHARP_,wport], null);
return (fret.cljs$core$IFn$_invoke$arity$1 ? fret.cljs$core$IFn$_invoke$arity$1(G__14961) : fret.call(null,G__14961));
});})(i,val,idx,port,wport,flag,ports__$1,n,_,idxs,priority))
));
})():cljs.core.async.impl.protocols.take_BANG_(port,cljs.core.async.alt_handler(flag,((function (i,idx,port,wport,flag,ports__$1,n,_,idxs,priority){
return (function (p1__14957_SHARP_){
var G__14962 = new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [p1__14957_SHARP_,port], null);
return (fret.cljs$core$IFn$_invoke$arity$1 ? fret.cljs$core$IFn$_invoke$arity$1(G__14962) : fret.call(null,G__14962));
});})(i,idx,port,wport,flag,ports__$1,n,_,idxs,priority))
)));
if(cljs.core.truth_(vbox)){
return cljs.core.async.impl.channels.box(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.core.deref(vbox),(function (){var or__5142__auto__ = wport;
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return port;
}
})()], null));
} else {
var G__17974 = (i + (1));
i = G__17974;
continue;
}
} else {
return null;
}
break;
}
})();
var or__5142__auto__ = ret;
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
if(cljs.core.contains_QMARK_(opts,new cljs.core.Keyword(null,"default","default",-1987822328))){
var temp__5823__auto__ = (function (){var and__5140__auto__ = flag.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1(null);
if(cljs.core.truth_(and__5140__auto__)){
return flag.cljs$core$async$impl$protocols$Handler$commit$arity$1(null);
} else {
return and__5140__auto__;
}
})();
if(cljs.core.truth_(temp__5823__auto__)){
var got = temp__5823__auto__;
return cljs.core.async.impl.channels.box(new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Keyword(null,"default","default",-1987822328).cljs$core$IFn$_invoke$arity$1(opts),new cljs.core.Keyword(null,"default","default",-1987822328)], null));
} else {
return null;
}
} else {
return null;
}
}
});
/**
 * Completes at most one of several channel operations. Must be called
 * inside a (go ...) block. ports is a vector of channel endpoints,
 * which can be either a channel to take from or a vector of
 *   [channel-to-put-to val-to-put], in any combination. Takes will be
 *   made as if by <!, and puts will be made as if by >!. Unless
 *   the :priority option is true, if more than one port operation is
 *   ready a non-deterministic choice will be made. If no operation is
 *   ready and a :default value is supplied, [default-val :default] will
 *   be returned, otherwise alts! will park until the first operation to
 *   become ready completes. Returns [val port] of the completed
 *   operation, where val is the value taken for takes, and a
 *   boolean (true unless already closed, as per put!) for puts.
 * 
 *   opts are passed as :key val ... Supported options:
 * 
 *   :default val - the value to use if none of the operations are immediately ready
 *   :priority true - (default nil) when true, the operations will be tried in order.
 * 
 *   Note: there is no guarantee that the port exps or val exprs will be
 *   used, nor in what order should they be, so they should not be
 *   depended upon for side effects.
 */
cljs.core.async.alts_BANG_ = (function cljs$core$async$alts_BANG_(var_args){
var args__5882__auto__ = [];
var len__5876__auto___17975 = arguments.length;
var i__5877__auto___17976 = (0);
while(true){
if((i__5877__auto___17976 < len__5876__auto___17975)){
args__5882__auto__.push((arguments[i__5877__auto___17976]));

var G__17977 = (i__5877__auto___17976 + (1));
i__5877__auto___17976 = G__17977;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((1) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((1)),(0),null)):null);
return cljs.core.async.alts_BANG_.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),argseq__5883__auto__);
});

(cljs.core.async.alts_BANG_.cljs$core$IFn$_invoke$arity$variadic = (function (ports,p__14975){
var map__14976 = p__14975;
var map__14976__$1 = cljs.core.__destructure_map(map__14976);
var opts = map__14976__$1;
throw (new Error("alts! used not in (go ...) block"));
}));

(cljs.core.async.alts_BANG_.cljs$lang$maxFixedArity = (1));

/** @this {Function} */
(cljs.core.async.alts_BANG_.cljs$lang$applyTo = (function (seq14969){
var G__14970 = cljs.core.first(seq14969);
var seq14969__$1 = cljs.core.next(seq14969);
var self__5861__auto__ = this;
return self__5861__auto__.cljs$core$IFn$_invoke$arity$variadic(G__14970,seq14969__$1);
}));

/**
 * Puts a val into port if it's possible to do so immediately.
 *   nil values are not allowed. Never blocks. Returns true if offer succeeds.
 */
cljs.core.async.offer_BANG_ = (function cljs$core$async$offer_BANG_(port,val){
var ret = cljs.core.async.impl.protocols.put_BANG_(port,val,cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2(cljs.core.async.nop,false));
if(cljs.core.truth_(ret)){
return cljs.core.deref(ret);
} else {
return null;
}
});
/**
 * Takes a val from port if it's possible to do so immediately.
 *   Never blocks. Returns value if successful, nil otherwise.
 */
cljs.core.async.poll_BANG_ = (function cljs$core$async$poll_BANG_(port){
var ret = cljs.core.async.impl.protocols.take_BANG_(port,cljs.core.async.fn_handler.cljs$core$IFn$_invoke$arity$2(cljs.core.async.nop,false));
if(cljs.core.truth_(ret)){
return cljs.core.deref(ret);
} else {
return null;
}
});
/**
 * Takes elements from the from channel and supplies them to the to
 * channel. By default, the to channel will be closed when the from
 * channel closes, but can be determined by the close?  parameter. Will
 * stop consuming the from channel if the to channel closes
 */
cljs.core.async.pipe = (function cljs$core$async$pipe(var_args){
var G__14991 = arguments.length;
switch (G__14991) {
case 2:
return cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$2 = (function (from,to){
return cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$3(from,to,true);
}));

(cljs.core.async.pipe.cljs$core$IFn$_invoke$arity$3 = (function (from,to,close_QMARK_){
var c__14503__auto___17995 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15064){
var state_val_15066 = (state_15064[(1)]);
if((state_val_15066 === (7))){
var inst_15055 = (state_15064[(2)]);
var state_15064__$1 = state_15064;
var statearr_15074_17996 = state_15064__$1;
(statearr_15074_17996[(2)] = inst_15055);

(statearr_15074_17996[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (1))){
var state_15064__$1 = state_15064;
var statearr_15075_17997 = state_15064__$1;
(statearr_15075_17997[(2)] = null);

(statearr_15075_17997[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (4))){
var inst_15031 = (state_15064[(7)]);
var inst_15031__$1 = (state_15064[(2)]);
var inst_15032 = (inst_15031__$1 == null);
var state_15064__$1 = (function (){var statearr_15077 = state_15064;
(statearr_15077[(7)] = inst_15031__$1);

return statearr_15077;
})();
if(cljs.core.truth_(inst_15032)){
var statearr_15078_18002 = state_15064__$1;
(statearr_15078_18002[(1)] = (5));

} else {
var statearr_15080_18003 = state_15064__$1;
(statearr_15080_18003[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (13))){
var state_15064__$1 = state_15064;
var statearr_15081_18008 = state_15064__$1;
(statearr_15081_18008[(2)] = null);

(statearr_15081_18008[(1)] = (14));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (6))){
var inst_15031 = (state_15064[(7)]);
var state_15064__$1 = state_15064;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_15064__$1,(11),to,inst_15031);
} else {
if((state_val_15066 === (3))){
var inst_15057 = (state_15064[(2)]);
var state_15064__$1 = state_15064;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15064__$1,inst_15057);
} else {
if((state_val_15066 === (12))){
var state_15064__$1 = state_15064;
var statearr_15085_18017 = state_15064__$1;
(statearr_15085_18017[(2)] = null);

(statearr_15085_18017[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (2))){
var state_15064__$1 = state_15064;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15064__$1,(4),from);
} else {
if((state_val_15066 === (11))){
var inst_15048 = (state_15064[(2)]);
var state_15064__$1 = state_15064;
if(cljs.core.truth_(inst_15048)){
var statearr_15087_18025 = state_15064__$1;
(statearr_15087_18025[(1)] = (12));

} else {
var statearr_15088_18026 = state_15064__$1;
(statearr_15088_18026[(1)] = (13));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (9))){
var state_15064__$1 = state_15064;
var statearr_15089_18027 = state_15064__$1;
(statearr_15089_18027[(2)] = null);

(statearr_15089_18027[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (5))){
var state_15064__$1 = state_15064;
if(cljs.core.truth_(close_QMARK_)){
var statearr_15091_18028 = state_15064__$1;
(statearr_15091_18028[(1)] = (8));

} else {
var statearr_15092_18029 = state_15064__$1;
(statearr_15092_18029[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (14))){
var inst_15053 = (state_15064[(2)]);
var state_15064__$1 = state_15064;
var statearr_15093_18030 = state_15064__$1;
(statearr_15093_18030[(2)] = inst_15053);

(statearr_15093_18030[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (10))){
var inst_15045 = (state_15064[(2)]);
var state_15064__$1 = state_15064;
var statearr_15094_18033 = state_15064__$1;
(statearr_15094_18033[(2)] = inst_15045);

(statearr_15094_18033[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15066 === (8))){
var inst_15041 = cljs.core.async.close_BANG_(to);
var state_15064__$1 = state_15064;
var statearr_15095_18040 = state_15064__$1;
(statearr_15095_18040[(2)] = inst_15041);

(statearr_15095_18040[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_15096 = [null,null,null,null,null,null,null,null];
(statearr_15096[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_15096[(1)] = (1));

return statearr_15096;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_15064){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15064);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15097){var ex__14017__auto__ = e15097;
var statearr_15098_18044 = state_15064;
(statearr_15098_18044[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15064[(4)]))){
var statearr_15099_18045 = state_15064;
(statearr_15099_18045[(1)] = cljs.core.first((state_15064[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18046 = state_15064;
state_15064 = G__18046;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_15064){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_15064);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_15102 = f__14504__auto__();
(statearr_15102[(6)] = c__14503__auto___17995);

return statearr_15102;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return to;
}));

(cljs.core.async.pipe.cljs$lang$maxFixedArity = 3);

cljs.core.async.pipeline_STAR_ = (function cljs$core$async$pipeline_STAR_(n,to,xf,from,close_QMARK_,ex_handler,type){
if((n > (0))){
} else {
throw (new Error("Assert failed: (pos? n)"));
}

var jobs = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(n);
var results = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(n);
var process__$1 = (function (p__15104){
var vec__15106 = p__15104;
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__15106,(0),null);
var p = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__15106,(1),null);
var job = vec__15106;
if((job == null)){
cljs.core.async.close_BANG_(results);

return null;
} else {
var res = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$3((1),xf,ex_handler);
var c__14503__auto___18048 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15113){
var state_val_15114 = (state_15113[(1)]);
if((state_val_15114 === (1))){
var state_15113__$1 = state_15113;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_15113__$1,(2),res,v);
} else {
if((state_val_15114 === (2))){
var inst_15110 = (state_15113[(2)]);
var inst_15111 = cljs.core.async.close_BANG_(res);
var state_15113__$1 = (function (){var statearr_15116 = state_15113;
(statearr_15116[(7)] = inst_15110);

return statearr_15116;
})();
return cljs.core.async.impl.ioc_helpers.return_chan(state_15113__$1,inst_15111);
} else {
return null;
}
}
});
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0 = (function (){
var statearr_15117 = [null,null,null,null,null,null,null,null];
(statearr_15117[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__);

(statearr_15117[(1)] = (1));

return statearr_15117;
});
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1 = (function (state_15113){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15113);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15119){var ex__14017__auto__ = e15119;
var statearr_15120_18050 = state_15113;
(statearr_15120_18050[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15113[(4)]))){
var statearr_15121_18051 = state_15113;
(statearr_15121_18051[(1)] = cljs.core.first((state_15113[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18052 = state_15113;
state_15113 = G__18052;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = function(state_15113){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1.call(this,state_15113);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_15124 = f__14504__auto__();
(statearr_15124[(6)] = c__14503__auto___18048);

return statearr_15124;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(p,res);

return true;
}
});
var async = (function (p__15126){
var vec__15128 = p__15126;
var v = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__15128,(0),null);
var p = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(vec__15128,(1),null);
var job = vec__15128;
if((job == null)){
cljs.core.async.close_BANG_(results);

return null;
} else {
var res = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
(xf.cljs$core$IFn$_invoke$arity$2 ? xf.cljs$core$IFn$_invoke$arity$2(v,res) : xf.call(null,v,res));

cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(p,res);

return true;
}
});
var n__5741__auto___18056 = n;
var __18057 = (0);
while(true){
if((__18057 < n__5741__auto___18056)){
var G__15133_18058 = type;
var G__15133_18059__$1 = (((G__15133_18058 instanceof cljs.core.Keyword))?G__15133_18058.fqn:null);
switch (G__15133_18059__$1) {
case "compute":
var c__14503__auto___18062 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run(((function (__18057,c__14503__auto___18062,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async){
return (function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = ((function (__18057,c__14503__auto___18062,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async){
return (function (state_15149){
var state_val_15150 = (state_15149[(1)]);
if((state_val_15150 === (1))){
var state_15149__$1 = state_15149;
var statearr_15151_18071 = state_15149__$1;
(statearr_15151_18071[(2)] = null);

(statearr_15151_18071[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15150 === (2))){
var state_15149__$1 = state_15149;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15149__$1,(4),jobs);
} else {
if((state_val_15150 === (3))){
var inst_15147 = (state_15149[(2)]);
var state_15149__$1 = state_15149;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15149__$1,inst_15147);
} else {
if((state_val_15150 === (4))){
var inst_15139 = (state_15149[(2)]);
var inst_15140 = process__$1(inst_15139);
var state_15149__$1 = state_15149;
if(cljs.core.truth_(inst_15140)){
var statearr_15156_18080 = state_15149__$1;
(statearr_15156_18080[(1)] = (5));

} else {
var statearr_15157_18081 = state_15149__$1;
(statearr_15157_18081[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15150 === (5))){
var state_15149__$1 = state_15149;
var statearr_15159_18082 = state_15149__$1;
(statearr_15159_18082[(2)] = null);

(statearr_15159_18082[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15150 === (6))){
var state_15149__$1 = state_15149;
var statearr_15162_18083 = state_15149__$1;
(statearr_15162_18083[(2)] = null);

(statearr_15162_18083[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15150 === (7))){
var inst_15145 = (state_15149[(2)]);
var state_15149__$1 = state_15149;
var statearr_15166_18085 = state_15149__$1;
(statearr_15166_18085[(2)] = inst_15145);

(statearr_15166_18085[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
});})(__18057,c__14503__auto___18062,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async))
;
return ((function (__18057,switch__14013__auto__,c__14503__auto___18062,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async){
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0 = (function (){
var statearr_15167 = [null,null,null,null,null,null,null];
(statearr_15167[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__);

(statearr_15167[(1)] = (1));

return statearr_15167;
});
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1 = (function (state_15149){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15149);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15168){var ex__14017__auto__ = e15168;
var statearr_15171_18099 = state_15149;
(statearr_15171_18099[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15149[(4)]))){
var statearr_15175_18101 = state_15149;
(statearr_15175_18101[(1)] = cljs.core.first((state_15149[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18102 = state_15149;
state_15149 = G__18102;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = function(state_15149){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1.call(this,state_15149);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__;
})()
;})(__18057,switch__14013__auto__,c__14503__auto___18062,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async))
})();
var state__14505__auto__ = (function (){var statearr_15178 = f__14504__auto__();
(statearr_15178[(6)] = c__14503__auto___18062);

return statearr_15178;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
});})(__18057,c__14503__auto___18062,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async))
);


break;
case "async":
var c__14503__auto___18107 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run(((function (__18057,c__14503__auto___18107,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async){
return (function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = ((function (__18057,c__14503__auto___18107,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async){
return (function (state_15195){
var state_val_15196 = (state_15195[(1)]);
if((state_val_15196 === (1))){
var state_15195__$1 = state_15195;
var statearr_15203_18110 = state_15195__$1;
(statearr_15203_18110[(2)] = null);

(statearr_15203_18110[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15196 === (2))){
var state_15195__$1 = state_15195;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15195__$1,(4),jobs);
} else {
if((state_val_15196 === (3))){
var inst_15193 = (state_15195[(2)]);
var state_15195__$1 = state_15195;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15195__$1,inst_15193);
} else {
if((state_val_15196 === (4))){
var inst_15185 = (state_15195[(2)]);
var inst_15186 = async(inst_15185);
var state_15195__$1 = state_15195;
if(cljs.core.truth_(inst_15186)){
var statearr_15206_18119 = state_15195__$1;
(statearr_15206_18119[(1)] = (5));

} else {
var statearr_15207_18120 = state_15195__$1;
(statearr_15207_18120[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15196 === (5))){
var state_15195__$1 = state_15195;
var statearr_15210_18122 = state_15195__$1;
(statearr_15210_18122[(2)] = null);

(statearr_15210_18122[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15196 === (6))){
var state_15195__$1 = state_15195;
var statearr_15215_18123 = state_15195__$1;
(statearr_15215_18123[(2)] = null);

(statearr_15215_18123[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15196 === (7))){
var inst_15191 = (state_15195[(2)]);
var state_15195__$1 = state_15195;
var statearr_15217_18124 = state_15195__$1;
(statearr_15217_18124[(2)] = inst_15191);

(statearr_15217_18124[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
});})(__18057,c__14503__auto___18107,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async))
;
return ((function (__18057,switch__14013__auto__,c__14503__auto___18107,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async){
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0 = (function (){
var statearr_15218 = [null,null,null,null,null,null,null];
(statearr_15218[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__);

(statearr_15218[(1)] = (1));

return statearr_15218;
});
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1 = (function (state_15195){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15195);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15219){var ex__14017__auto__ = e15219;
var statearr_15223_18137 = state_15195;
(statearr_15223_18137[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15195[(4)]))){
var statearr_15227_18140 = state_15195;
(statearr_15227_18140[(1)] = cljs.core.first((state_15195[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18142 = state_15195;
state_15195 = G__18142;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = function(state_15195){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1.call(this,state_15195);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__;
})()
;})(__18057,switch__14013__auto__,c__14503__auto___18107,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async))
})();
var state__14505__auto__ = (function (){var statearr_15234 = f__14504__auto__();
(statearr_15234[(6)] = c__14503__auto___18107);

return statearr_15234;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
});})(__18057,c__14503__auto___18107,G__15133_18058,G__15133_18059__$1,n__5741__auto___18056,jobs,results,process__$1,async))
);


break;
default:
throw (new Error((""+"No matching clause: "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(G__15133_18059__$1))));

}

var G__18145 = (__18057 + (1));
__18057 = G__18145;
continue;
} else {
}
break;
}

var c__14503__auto___18146 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15261){
var state_val_15262 = (state_15261[(1)]);
if((state_val_15262 === (7))){
var inst_15256 = (state_15261[(2)]);
var state_15261__$1 = state_15261;
var statearr_15263_18150 = state_15261__$1;
(statearr_15263_18150[(2)] = inst_15256);

(statearr_15263_18150[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15262 === (1))){
var state_15261__$1 = state_15261;
var statearr_15264_18153 = state_15261__$1;
(statearr_15264_18153[(2)] = null);

(statearr_15264_18153[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15262 === (4))){
var inst_15237 = (state_15261[(7)]);
var inst_15237__$1 = (state_15261[(2)]);
var inst_15239 = (inst_15237__$1 == null);
var state_15261__$1 = (function (){var statearr_15266 = state_15261;
(statearr_15266[(7)] = inst_15237__$1);

return statearr_15266;
})();
if(cljs.core.truth_(inst_15239)){
var statearr_15269_18157 = state_15261__$1;
(statearr_15269_18157[(1)] = (5));

} else {
var statearr_15271_18161 = state_15261__$1;
(statearr_15271_18161[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15262 === (6))){
var inst_15237 = (state_15261[(7)]);
var inst_15243 = (state_15261[(8)]);
var inst_15243__$1 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
var inst_15247 = cljs.core.PersistentVector.EMPTY_NODE;
var inst_15248 = [inst_15237,inst_15243__$1];
var inst_15249 = (new cljs.core.PersistentVector(null,2,(5),inst_15247,inst_15248,null));
var state_15261__$1 = (function (){var statearr_15273 = state_15261;
(statearr_15273[(8)] = inst_15243__$1);

return statearr_15273;
})();
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_15261__$1,(8),jobs,inst_15249);
} else {
if((state_val_15262 === (3))){
var inst_15259 = (state_15261[(2)]);
var state_15261__$1 = state_15261;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15261__$1,inst_15259);
} else {
if((state_val_15262 === (2))){
var state_15261__$1 = state_15261;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15261__$1,(4),from);
} else {
if((state_val_15262 === (9))){
var inst_15253 = (state_15261[(2)]);
var state_15261__$1 = (function (){var statearr_15274 = state_15261;
(statearr_15274[(9)] = inst_15253);

return statearr_15274;
})();
var statearr_15275_18164 = state_15261__$1;
(statearr_15275_18164[(2)] = null);

(statearr_15275_18164[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15262 === (5))){
var inst_15241 = cljs.core.async.close_BANG_(jobs);
var state_15261__$1 = state_15261;
var statearr_15276_18165 = state_15261__$1;
(statearr_15276_18165[(2)] = inst_15241);

(statearr_15276_18165[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15262 === (8))){
var inst_15243 = (state_15261[(8)]);
var inst_15251 = (state_15261[(2)]);
var state_15261__$1 = (function (){var statearr_15277 = state_15261;
(statearr_15277[(10)] = inst_15251);

return statearr_15277;
})();
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_15261__$1,(9),results,inst_15243);
} else {
return null;
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0 = (function (){
var statearr_15278 = [null,null,null,null,null,null,null,null,null,null,null];
(statearr_15278[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__);

(statearr_15278[(1)] = (1));

return statearr_15278;
});
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1 = (function (state_15261){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15261);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15280){var ex__14017__auto__ = e15280;
var statearr_15281_18174 = state_15261;
(statearr_15281_18174[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15261[(4)]))){
var statearr_15282_18178 = state_15261;
(statearr_15282_18178[(1)] = cljs.core.first((state_15261[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18179 = state_15261;
state_15261 = G__18179;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = function(state_15261){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1.call(this,state_15261);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_15286 = f__14504__auto__();
(statearr_15286[(6)] = c__14503__auto___18146);

return statearr_15286;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


var c__14503__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15333){
var state_val_15334 = (state_15333[(1)]);
if((state_val_15334 === (7))){
var inst_15327 = (state_15333[(2)]);
var state_15333__$1 = state_15333;
var statearr_15339_18180 = state_15333__$1;
(statearr_15339_18180[(2)] = inst_15327);

(statearr_15339_18180[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (20))){
var state_15333__$1 = state_15333;
var statearr_15340_18181 = state_15333__$1;
(statearr_15340_18181[(2)] = null);

(statearr_15340_18181[(1)] = (21));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (1))){
var state_15333__$1 = state_15333;
var statearr_15345_18182 = state_15333__$1;
(statearr_15345_18182[(2)] = null);

(statearr_15345_18182[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (4))){
var inst_15290 = (state_15333[(7)]);
var inst_15290__$1 = (state_15333[(2)]);
var inst_15291 = (inst_15290__$1 == null);
var state_15333__$1 = (function (){var statearr_15347 = state_15333;
(statearr_15347[(7)] = inst_15290__$1);

return statearr_15347;
})();
if(cljs.core.truth_(inst_15291)){
var statearr_15351_18185 = state_15333__$1;
(statearr_15351_18185[(1)] = (5));

} else {
var statearr_15355_18186 = state_15333__$1;
(statearr_15355_18186[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (15))){
var inst_15306 = (state_15333[(8)]);
var state_15333__$1 = state_15333;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_15333__$1,(18),to,inst_15306);
} else {
if((state_val_15334 === (21))){
var inst_15321 = (state_15333[(2)]);
var state_15333__$1 = state_15333;
var statearr_15357_18187 = state_15333__$1;
(statearr_15357_18187[(2)] = inst_15321);

(statearr_15357_18187[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (13))){
var inst_15323 = (state_15333[(2)]);
var state_15333__$1 = (function (){var statearr_15362 = state_15333;
(statearr_15362[(9)] = inst_15323);

return statearr_15362;
})();
var statearr_15363_18189 = state_15333__$1;
(statearr_15363_18189[(2)] = null);

(statearr_15363_18189[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (6))){
var inst_15290 = (state_15333[(7)]);
var state_15333__$1 = state_15333;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15333__$1,(11),inst_15290);
} else {
if((state_val_15334 === (17))){
var inst_15315 = (state_15333[(2)]);
var state_15333__$1 = state_15333;
if(cljs.core.truth_(inst_15315)){
var statearr_15367_18191 = state_15333__$1;
(statearr_15367_18191[(1)] = (19));

} else {
var statearr_15368_18192 = state_15333__$1;
(statearr_15368_18192[(1)] = (20));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (3))){
var inst_15330 = (state_15333[(2)]);
var state_15333__$1 = state_15333;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15333__$1,inst_15330);
} else {
if((state_val_15334 === (12))){
var inst_15303 = (state_15333[(10)]);
var state_15333__$1 = state_15333;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15333__$1,(14),inst_15303);
} else {
if((state_val_15334 === (2))){
var state_15333__$1 = state_15333;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15333__$1,(4),results);
} else {
if((state_val_15334 === (19))){
var state_15333__$1 = state_15333;
var statearr_15370_18196 = state_15333__$1;
(statearr_15370_18196[(2)] = null);

(statearr_15370_18196[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (11))){
var inst_15303 = (state_15333[(2)]);
var state_15333__$1 = (function (){var statearr_15371 = state_15333;
(statearr_15371[(10)] = inst_15303);

return statearr_15371;
})();
var statearr_15372_18200 = state_15333__$1;
(statearr_15372_18200[(2)] = null);

(statearr_15372_18200[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (9))){
var state_15333__$1 = state_15333;
var statearr_15373_18201 = state_15333__$1;
(statearr_15373_18201[(2)] = null);

(statearr_15373_18201[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (5))){
var state_15333__$1 = state_15333;
if(cljs.core.truth_(close_QMARK_)){
var statearr_15374_18202 = state_15333__$1;
(statearr_15374_18202[(1)] = (8));

} else {
var statearr_15375_18203 = state_15333__$1;
(statearr_15375_18203[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (14))){
var inst_15306 = (state_15333[(8)]);
var inst_15308 = (state_15333[(11)]);
var inst_15306__$1 = (state_15333[(2)]);
var inst_15307 = (inst_15306__$1 == null);
var inst_15308__$1 = cljs.core.not(inst_15307);
var state_15333__$1 = (function (){var statearr_15377 = state_15333;
(statearr_15377[(8)] = inst_15306__$1);

(statearr_15377[(11)] = inst_15308__$1);

return statearr_15377;
})();
if(inst_15308__$1){
var statearr_15378_18204 = state_15333__$1;
(statearr_15378_18204[(1)] = (15));

} else {
var statearr_15379_18205 = state_15333__$1;
(statearr_15379_18205[(1)] = (16));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (16))){
var inst_15308 = (state_15333[(11)]);
var state_15333__$1 = state_15333;
var statearr_15381_18206 = state_15333__$1;
(statearr_15381_18206[(2)] = inst_15308);

(statearr_15381_18206[(1)] = (17));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (10))){
var inst_15297 = (state_15333[(2)]);
var state_15333__$1 = state_15333;
var statearr_15382_18207 = state_15333__$1;
(statearr_15382_18207[(2)] = inst_15297);

(statearr_15382_18207[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (18))){
var inst_15312 = (state_15333[(2)]);
var state_15333__$1 = state_15333;
var statearr_15383_18211 = state_15333__$1;
(statearr_15383_18211[(2)] = inst_15312);

(statearr_15383_18211[(1)] = (17));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15334 === (8))){
var inst_15294 = cljs.core.async.close_BANG_(to);
var state_15333__$1 = state_15333;
var statearr_15384_18212 = state_15333__$1;
(statearr_15384_18212[(2)] = inst_15294);

(statearr_15384_18212[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = null;
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0 = (function (){
var statearr_15389 = [null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_15389[(0)] = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__);

(statearr_15389[(1)] = (1));

return statearr_15389;
});
var cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1 = (function (state_15333){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15333);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15392){var ex__14017__auto__ = e15392;
var statearr_15393_18213 = state_15333;
(statearr_15393_18213[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15333[(4)]))){
var statearr_15394_18214 = state_15333;
(statearr_15394_18214[(1)] = cljs.core.first((state_15333[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18215 = state_15333;
state_15333 = G__18215;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__ = function(state_15333){
switch(arguments.length){
case 0:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1.call(this,state_15333);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____0;
cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$pipeline_STAR__$_state_machine__14014__auto____1;
return cljs$core$async$pipeline_STAR__$_state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_15396 = f__14504__auto__();
(statearr_15396[(6)] = c__14503__auto__);

return statearr_15396;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));

return c__14503__auto__;
});
/**
 * Takes elements from the from channel and supplies them to the to
 *   channel, subject to the async function af, with parallelism n. af
 *   must be a function of two arguments, the first an input value and
 *   the second a channel on which to place the result(s). The
 *   presumption is that af will return immediately, having launched some
 *   asynchronous operation whose completion/callback will put results on
 *   the channel, then close! it. Outputs will be returned in order
 *   relative to the inputs. By default, the to channel will be closed
 *   when the from channel closes, but can be determined by the close?
 *   parameter. Will stop consuming the from channel if the to channel
 *   closes. See also pipeline, pipeline-blocking.
 */
cljs.core.async.pipeline_async = (function cljs$core$async$pipeline_async(var_args){
var G__15399 = arguments.length;
switch (G__15399) {
case 4:
return cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
case 5:
return cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$5((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]),(arguments[(4)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$4 = (function (n,to,af,from){
return cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$5(n,to,af,from,true);
}));

(cljs.core.async.pipeline_async.cljs$core$IFn$_invoke$arity$5 = (function (n,to,af,from,close_QMARK_){
return cljs.core.async.pipeline_STAR_(n,to,af,from,close_QMARK_,null,new cljs.core.Keyword(null,"async","async",1050769601));
}));

(cljs.core.async.pipeline_async.cljs$lang$maxFixedArity = 5);

/**
 * Takes elements from the from channel and supplies them to the to
 *   channel, subject to the transducer xf, with parallelism n. Because
 *   it is parallel, the transducer will be applied independently to each
 *   element, not across elements, and may produce zero or more outputs
 *   per input.  Outputs will be returned in order relative to the
 *   inputs. By default, the to channel will be closed when the from
 *   channel closes, but can be determined by the close?  parameter. Will
 *   stop consuming the from channel if the to channel closes.
 * 
 *   Note this is supplied for API compatibility with the Clojure version.
 *   Values of N > 1 will not result in actual concurrency in a
 *   single-threaded runtime.
 */
cljs.core.async.pipeline = (function cljs$core$async$pipeline(var_args){
var G__15406 = arguments.length;
switch (G__15406) {
case 4:
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
case 5:
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$5((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]),(arguments[(4)]));

break;
case 6:
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$6((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]),(arguments[(4)]),(arguments[(5)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$4 = (function (n,to,xf,from){
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$5(n,to,xf,from,true);
}));

(cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$5 = (function (n,to,xf,from,close_QMARK_){
return cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$6(n,to,xf,from,close_QMARK_,null);
}));

(cljs.core.async.pipeline.cljs$core$IFn$_invoke$arity$6 = (function (n,to,xf,from,close_QMARK_,ex_handler){
return cljs.core.async.pipeline_STAR_(n,to,xf,from,close_QMARK_,ex_handler,new cljs.core.Keyword(null,"compute","compute",1555393130));
}));

(cljs.core.async.pipeline.cljs$lang$maxFixedArity = 6);

/**
 * Takes a predicate and a source channel and returns a vector of two
 *   channels, the first of which will contain the values for which the
 *   predicate returned true, the second those for which it returned
 *   false.
 * 
 *   The out channels will be unbuffered by default, or two buf-or-ns can
 *   be supplied. The channels will close after the source channel has
 *   closed.
 */
cljs.core.async.split = (function cljs$core$async$split(var_args){
var G__15409 = arguments.length;
switch (G__15409) {
case 2:
return cljs.core.async.split.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 4:
return cljs.core.async.split.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.split.cljs$core$IFn$_invoke$arity$2 = (function (p,ch){
return cljs.core.async.split.cljs$core$IFn$_invoke$arity$4(p,ch,null,null);
}));

(cljs.core.async.split.cljs$core$IFn$_invoke$arity$4 = (function (p,ch,t_buf_or_n,f_buf_or_n){
var tc = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(t_buf_or_n);
var fc = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(f_buf_or_n);
var c__14503__auto___18236 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15436){
var state_val_15438 = (state_15436[(1)]);
if((state_val_15438 === (7))){
var inst_15432 = (state_15436[(2)]);
var state_15436__$1 = state_15436;
var statearr_15444_18237 = state_15436__$1;
(statearr_15444_18237[(2)] = inst_15432);

(statearr_15444_18237[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (1))){
var state_15436__$1 = state_15436;
var statearr_15447_18238 = state_15436__$1;
(statearr_15447_18238[(2)] = null);

(statearr_15447_18238[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (4))){
var inst_15413 = (state_15436[(7)]);
var inst_15413__$1 = (state_15436[(2)]);
var inst_15414 = (inst_15413__$1 == null);
var state_15436__$1 = (function (){var statearr_15451 = state_15436;
(statearr_15451[(7)] = inst_15413__$1);

return statearr_15451;
})();
if(cljs.core.truth_(inst_15414)){
var statearr_15452_18239 = state_15436__$1;
(statearr_15452_18239[(1)] = (5));

} else {
var statearr_15453_18240 = state_15436__$1;
(statearr_15453_18240[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (13))){
var state_15436__$1 = state_15436;
var statearr_15454_18242 = state_15436__$1;
(statearr_15454_18242[(2)] = null);

(statearr_15454_18242[(1)] = (14));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (6))){
var inst_15413 = (state_15436[(7)]);
var inst_15419 = (p.cljs$core$IFn$_invoke$arity$1 ? p.cljs$core$IFn$_invoke$arity$1(inst_15413) : p.call(null,inst_15413));
var state_15436__$1 = state_15436;
if(cljs.core.truth_(inst_15419)){
var statearr_15455_18246 = state_15436__$1;
(statearr_15455_18246[(1)] = (9));

} else {
var statearr_15456_18247 = state_15436__$1;
(statearr_15456_18247[(1)] = (10));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (3))){
var inst_15434 = (state_15436[(2)]);
var state_15436__$1 = state_15436;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15436__$1,inst_15434);
} else {
if((state_val_15438 === (12))){
var state_15436__$1 = state_15436;
var statearr_15458_18251 = state_15436__$1;
(statearr_15458_18251[(2)] = null);

(statearr_15458_18251[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (2))){
var state_15436__$1 = state_15436;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15436__$1,(4),ch);
} else {
if((state_val_15438 === (11))){
var inst_15413 = (state_15436[(7)]);
var inst_15423 = (state_15436[(2)]);
var state_15436__$1 = state_15436;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_15436__$1,(8),inst_15423,inst_15413);
} else {
if((state_val_15438 === (9))){
var state_15436__$1 = state_15436;
var statearr_15459_18252 = state_15436__$1;
(statearr_15459_18252[(2)] = tc);

(statearr_15459_18252[(1)] = (11));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (5))){
var inst_15416 = cljs.core.async.close_BANG_(tc);
var inst_15417 = cljs.core.async.close_BANG_(fc);
var state_15436__$1 = (function (){var statearr_15460 = state_15436;
(statearr_15460[(8)] = inst_15416);

return statearr_15460;
})();
var statearr_15461_18253 = state_15436__$1;
(statearr_15461_18253[(2)] = inst_15417);

(statearr_15461_18253[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (14))){
var inst_15430 = (state_15436[(2)]);
var state_15436__$1 = state_15436;
var statearr_15464_18254 = state_15436__$1;
(statearr_15464_18254[(2)] = inst_15430);

(statearr_15464_18254[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (10))){
var state_15436__$1 = state_15436;
var statearr_15469_18255 = state_15436__$1;
(statearr_15469_18255[(2)] = fc);

(statearr_15469_18255[(1)] = (11));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15438 === (8))){
var inst_15425 = (state_15436[(2)]);
var state_15436__$1 = state_15436;
if(cljs.core.truth_(inst_15425)){
var statearr_15470_18256 = state_15436__$1;
(statearr_15470_18256[(1)] = (12));

} else {
var statearr_15471_18257 = state_15436__$1;
(statearr_15471_18257[(1)] = (13));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_15472 = [null,null,null,null,null,null,null,null,null];
(statearr_15472[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_15472[(1)] = (1));

return statearr_15472;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_15436){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15436);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15473){var ex__14017__auto__ = e15473;
var statearr_15474_18267 = state_15436;
(statearr_15474_18267[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15436[(4)]))){
var statearr_15475_18268 = state_15436;
(statearr_15475_18268[(1)] = cljs.core.first((state_15436[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18269 = state_15436;
state_15436 = G__18269;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_15436){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_15436);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_15481 = f__14504__auto__();
(statearr_15481[(6)] = c__14503__auto___18236);

return statearr_15481;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [tc,fc], null);
}));

(cljs.core.async.split.cljs$lang$maxFixedArity = 4);

/**
 * f should be a function of 2 arguments. Returns a channel containing
 *   the single result of applying f to init and the first item from the
 *   channel, then applying f to that result and the 2nd item, etc. If
 *   the channel closes without yielding items, returns init and f is not
 *   called. ch must close before reduce produces a result.
 */
cljs.core.async.reduce = (function cljs$core$async$reduce(f,init,ch){
var c__14503__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15510){
var state_val_15511 = (state_15510[(1)]);
if((state_val_15511 === (7))){
var inst_15506 = (state_15510[(2)]);
var state_15510__$1 = state_15510;
var statearr_15516_18274 = state_15510__$1;
(statearr_15516_18274[(2)] = inst_15506);

(statearr_15516_18274[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15511 === (1))){
var inst_15487 = init;
var inst_15488 = inst_15487;
var state_15510__$1 = (function (){var statearr_15517 = state_15510;
(statearr_15517[(7)] = inst_15488);

return statearr_15517;
})();
var statearr_15518_18278 = state_15510__$1;
(statearr_15518_18278[(2)] = null);

(statearr_15518_18278[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15511 === (4))){
var inst_15492 = (state_15510[(8)]);
var inst_15492__$1 = (state_15510[(2)]);
var inst_15493 = (inst_15492__$1 == null);
var state_15510__$1 = (function (){var statearr_15520 = state_15510;
(statearr_15520[(8)] = inst_15492__$1);

return statearr_15520;
})();
if(cljs.core.truth_(inst_15493)){
var statearr_15521_18283 = state_15510__$1;
(statearr_15521_18283[(1)] = (5));

} else {
var statearr_15522_18284 = state_15510__$1;
(statearr_15522_18284[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15511 === (6))){
var inst_15488 = (state_15510[(7)]);
var inst_15492 = (state_15510[(8)]);
var inst_15497 = (state_15510[(9)]);
var inst_15497__$1 = (f.cljs$core$IFn$_invoke$arity$2 ? f.cljs$core$IFn$_invoke$arity$2(inst_15488,inst_15492) : f.call(null,inst_15488,inst_15492));
var inst_15498 = cljs.core.reduced_QMARK_(inst_15497__$1);
var state_15510__$1 = (function (){var statearr_15523 = state_15510;
(statearr_15523[(9)] = inst_15497__$1);

return statearr_15523;
})();
if(inst_15498){
var statearr_15524_18285 = state_15510__$1;
(statearr_15524_18285[(1)] = (8));

} else {
var statearr_15525_18286 = state_15510__$1;
(statearr_15525_18286[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15511 === (3))){
var inst_15508 = (state_15510[(2)]);
var state_15510__$1 = state_15510;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15510__$1,inst_15508);
} else {
if((state_val_15511 === (2))){
var state_15510__$1 = state_15510;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15510__$1,(4),ch);
} else {
if((state_val_15511 === (9))){
var inst_15497 = (state_15510[(9)]);
var inst_15488 = inst_15497;
var state_15510__$1 = (function (){var statearr_15526 = state_15510;
(statearr_15526[(7)] = inst_15488);

return statearr_15526;
})();
var statearr_15527_18287 = state_15510__$1;
(statearr_15527_18287[(2)] = null);

(statearr_15527_18287[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15511 === (5))){
var inst_15488 = (state_15510[(7)]);
var state_15510__$1 = state_15510;
var statearr_15529_18291 = state_15510__$1;
(statearr_15529_18291[(2)] = inst_15488);

(statearr_15529_18291[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15511 === (10))){
var inst_15504 = (state_15510[(2)]);
var state_15510__$1 = state_15510;
var statearr_15530_18292 = state_15510__$1;
(statearr_15530_18292[(2)] = inst_15504);

(statearr_15530_18292[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15511 === (8))){
var inst_15497 = (state_15510[(9)]);
var inst_15500 = cljs.core.deref(inst_15497);
var state_15510__$1 = state_15510;
var statearr_15531_18293 = state_15510__$1;
(statearr_15531_18293[(2)] = inst_15500);

(statearr_15531_18293[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$reduce_$_state_machine__14014__auto__ = null;
var cljs$core$async$reduce_$_state_machine__14014__auto____0 = (function (){
var statearr_15532 = [null,null,null,null,null,null,null,null,null,null];
(statearr_15532[(0)] = cljs$core$async$reduce_$_state_machine__14014__auto__);

(statearr_15532[(1)] = (1));

return statearr_15532;
});
var cljs$core$async$reduce_$_state_machine__14014__auto____1 = (function (state_15510){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15510);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15533){var ex__14017__auto__ = e15533;
var statearr_15534_18297 = state_15510;
(statearr_15534_18297[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15510[(4)]))){
var statearr_15535_18298 = state_15510;
(statearr_15535_18298[(1)] = cljs.core.first((state_15510[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18299 = state_15510;
state_15510 = G__18299;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$reduce_$_state_machine__14014__auto__ = function(state_15510){
switch(arguments.length){
case 0:
return cljs$core$async$reduce_$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$reduce_$_state_machine__14014__auto____1.call(this,state_15510);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$reduce_$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$reduce_$_state_machine__14014__auto____0;
cljs$core$async$reduce_$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$reduce_$_state_machine__14014__auto____1;
return cljs$core$async$reduce_$_state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_15541 = f__14504__auto__();
(statearr_15541[(6)] = c__14503__auto__);

return statearr_15541;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));

return c__14503__auto__;
});
/**
 * async/reduces a channel with a transformation (xform f).
 *   Returns a channel containing the result.  ch must close before
 *   transduce produces a result.
 */
cljs.core.async.transduce = (function cljs$core$async$transduce(xform,f,init,ch){
var f__$1 = (xform.cljs$core$IFn$_invoke$arity$1 ? xform.cljs$core$IFn$_invoke$arity$1(f) : xform.call(null,f));
var c__14503__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15552){
var state_val_15553 = (state_15552[(1)]);
if((state_val_15553 === (1))){
var inst_15547 = cljs.core.async.reduce(f__$1,init,ch);
var state_15552__$1 = state_15552;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15552__$1,(2),inst_15547);
} else {
if((state_val_15553 === (2))){
var inst_15549 = (state_15552[(2)]);
var inst_15550 = (f__$1.cljs$core$IFn$_invoke$arity$1 ? f__$1.cljs$core$IFn$_invoke$arity$1(inst_15549) : f__$1.call(null,inst_15549));
var state_15552__$1 = state_15552;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15552__$1,inst_15550);
} else {
return null;
}
}
});
return (function() {
var cljs$core$async$transduce_$_state_machine__14014__auto__ = null;
var cljs$core$async$transduce_$_state_machine__14014__auto____0 = (function (){
var statearr_15556 = [null,null,null,null,null,null,null];
(statearr_15556[(0)] = cljs$core$async$transduce_$_state_machine__14014__auto__);

(statearr_15556[(1)] = (1));

return statearr_15556;
});
var cljs$core$async$transduce_$_state_machine__14014__auto____1 = (function (state_15552){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15552);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15558){var ex__14017__auto__ = e15558;
var statearr_15560_18307 = state_15552;
(statearr_15560_18307[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15552[(4)]))){
var statearr_15563_18308 = state_15552;
(statearr_15563_18308[(1)] = cljs.core.first((state_15552[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18309 = state_15552;
state_15552 = G__18309;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$transduce_$_state_machine__14014__auto__ = function(state_15552){
switch(arguments.length){
case 0:
return cljs$core$async$transduce_$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$transduce_$_state_machine__14014__auto____1.call(this,state_15552);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$transduce_$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$transduce_$_state_machine__14014__auto____0;
cljs$core$async$transduce_$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$transduce_$_state_machine__14014__auto____1;
return cljs$core$async$transduce_$_state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_15564 = f__14504__auto__();
(statearr_15564[(6)] = c__14503__auto__);

return statearr_15564;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));

return c__14503__auto__;
});
/**
 * Puts the contents of coll into the supplied channel.
 * 
 *   By default the channel will be closed after the items are copied,
 *   but can be determined by the close? parameter.
 * 
 *   Returns a channel which will close after the items are copied.
 */
cljs.core.async.onto_chan_BANG_ = (function cljs$core$async$onto_chan_BANG_(var_args){
var G__15568 = arguments.length;
switch (G__15568) {
case 2:
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$2 = (function (ch,coll){
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3(ch,coll,true);
}));

(cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3 = (function (ch,coll,close_QMARK_){
var c__14503__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15607){
var state_val_15608 = (state_15607[(1)]);
if((state_val_15608 === (7))){
var inst_15586 = (state_15607[(2)]);
var state_15607__$1 = state_15607;
var statearr_15612_18311 = state_15607__$1;
(statearr_15612_18311[(2)] = inst_15586);

(statearr_15612_18311[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (1))){
var inst_15578 = cljs.core.seq(coll);
var inst_15579 = inst_15578;
var state_15607__$1 = (function (){var statearr_15619 = state_15607;
(statearr_15619[(7)] = inst_15579);

return statearr_15619;
})();
var statearr_15620_18315 = state_15607__$1;
(statearr_15620_18315[(2)] = null);

(statearr_15620_18315[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (4))){
var inst_15579 = (state_15607[(7)]);
var inst_15584 = cljs.core.first(inst_15579);
var state_15607__$1 = state_15607;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_15607__$1,(7),ch,inst_15584);
} else {
if((state_val_15608 === (13))){
var inst_15601 = (state_15607[(2)]);
var state_15607__$1 = state_15607;
var statearr_15628_18318 = state_15607__$1;
(statearr_15628_18318[(2)] = inst_15601);

(statearr_15628_18318[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (6))){
var inst_15589 = (state_15607[(2)]);
var state_15607__$1 = state_15607;
if(cljs.core.truth_(inst_15589)){
var statearr_15630_18323 = state_15607__$1;
(statearr_15630_18323[(1)] = (8));

} else {
var statearr_15631_18325 = state_15607__$1;
(statearr_15631_18325[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (3))){
var inst_15605 = (state_15607[(2)]);
var state_15607__$1 = state_15607;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15607__$1,inst_15605);
} else {
if((state_val_15608 === (12))){
var state_15607__$1 = state_15607;
var statearr_15634_18326 = state_15607__$1;
(statearr_15634_18326[(2)] = null);

(statearr_15634_18326[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (2))){
var inst_15579 = (state_15607[(7)]);
var state_15607__$1 = state_15607;
if(cljs.core.truth_(inst_15579)){
var statearr_15638_18328 = state_15607__$1;
(statearr_15638_18328[(1)] = (4));

} else {
var statearr_15640_18329 = state_15607__$1;
(statearr_15640_18329[(1)] = (5));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (11))){
var inst_15598 = cljs.core.async.close_BANG_(ch);
var state_15607__$1 = state_15607;
var statearr_15641_18330 = state_15607__$1;
(statearr_15641_18330[(2)] = inst_15598);

(statearr_15641_18330[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (9))){
var state_15607__$1 = state_15607;
if(cljs.core.truth_(close_QMARK_)){
var statearr_15644_18334 = state_15607__$1;
(statearr_15644_18334[(1)] = (11));

} else {
var statearr_15645_18335 = state_15607__$1;
(statearr_15645_18335[(1)] = (12));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (5))){
var inst_15579 = (state_15607[(7)]);
var state_15607__$1 = state_15607;
var statearr_15647_18336 = state_15607__$1;
(statearr_15647_18336[(2)] = inst_15579);

(statearr_15647_18336[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (10))){
var inst_15603 = (state_15607[(2)]);
var state_15607__$1 = state_15607;
var statearr_15650_18340 = state_15607__$1;
(statearr_15650_18340[(2)] = inst_15603);

(statearr_15650_18340[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15608 === (8))){
var inst_15579 = (state_15607[(7)]);
var inst_15592 = cljs.core.next(inst_15579);
var inst_15579__$1 = inst_15592;
var state_15607__$1 = (function (){var statearr_15653 = state_15607;
(statearr_15653[(7)] = inst_15579__$1);

return statearr_15653;
})();
var statearr_15655_18346 = state_15607__$1;
(statearr_15655_18346[(2)] = null);

(statearr_15655_18346[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_15660 = [null,null,null,null,null,null,null,null];
(statearr_15660[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_15660[(1)] = (1));

return statearr_15660;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_15607){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15607);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e15661){var ex__14017__auto__ = e15661;
var statearr_15663_18354 = state_15607;
(statearr_15663_18354[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15607[(4)]))){
var statearr_15664_18355 = state_15607;
(statearr_15664_18355[(1)] = cljs.core.first((state_15607[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18356 = state_15607;
state_15607 = G__18356;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_15607){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_15607);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_15668 = f__14504__auto__();
(statearr_15668[(6)] = c__14503__auto__);

return statearr_15668;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));

return c__14503__auto__;
}));

(cljs.core.async.onto_chan_BANG_.cljs$lang$maxFixedArity = 3);

/**
 * Creates and returns a channel which contains the contents of coll,
 *   closing when exhausted.
 */
cljs.core.async.to_chan_BANG_ = (function cljs$core$async$to_chan_BANG_(coll){
var ch = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(cljs.core.bounded_count((100),coll));
cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$2(ch,coll);

return ch;
});
/**
 * Deprecated - use onto-chan!
 */
cljs.core.async.onto_chan = (function cljs$core$async$onto_chan(var_args){
var G__15683 = arguments.length;
switch (G__15683) {
case 2:
return cljs.core.async.onto_chan.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.onto_chan.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.onto_chan.cljs$core$IFn$_invoke$arity$2 = (function (ch,coll){
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3(ch,coll,true);
}));

(cljs.core.async.onto_chan.cljs$core$IFn$_invoke$arity$3 = (function (ch,coll,close_QMARK_){
return cljs.core.async.onto_chan_BANG_.cljs$core$IFn$_invoke$arity$3(ch,coll,close_QMARK_);
}));

(cljs.core.async.onto_chan.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - use to-chan!
 */
cljs.core.async.to_chan = (function cljs$core$async$to_chan(coll){
return cljs.core.async.to_chan_BANG_(coll);
});

/**
 * @interface
 */
cljs.core.async.Mux = function(){};

var cljs$core$async$Mux$muxch_STAR_$dyn_18373 = (function (_){
var x__5498__auto__ = (((_ == null))?null:_);
var m__5499__auto__ = (cljs.core.async.muxch_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$1(_) : m__5499__auto__.call(null,_));
} else {
var m__5497__auto__ = (cljs.core.async.muxch_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$1(_) : m__5497__auto__.call(null,_));
} else {
throw cljs.core.missing_protocol("Mux.muxch*",_);
}
}
});
cljs.core.async.muxch_STAR_ = (function cljs$core$async$muxch_STAR_(_){
if((((!((_ == null)))) && ((!((_.cljs$core$async$Mux$muxch_STAR_$arity$1 == null)))))){
return _.cljs$core$async$Mux$muxch_STAR_$arity$1(_);
} else {
return cljs$core$async$Mux$muxch_STAR_$dyn_18373(_);
}
});


/**
 * @interface
 */
cljs.core.async.Mult = function(){};

var cljs$core$async$Mult$tap_STAR_$dyn_18374 = (function (m,ch,close_QMARK_){
var x__5498__auto__ = (((m == null))?null:m);
var m__5499__auto__ = (cljs.core.async.tap_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$3 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$3(m,ch,close_QMARK_) : m__5499__auto__.call(null,m,ch,close_QMARK_));
} else {
var m__5497__auto__ = (cljs.core.async.tap_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$3 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$3(m,ch,close_QMARK_) : m__5497__auto__.call(null,m,ch,close_QMARK_));
} else {
throw cljs.core.missing_protocol("Mult.tap*",m);
}
}
});
cljs.core.async.tap_STAR_ = (function cljs$core$async$tap_STAR_(m,ch,close_QMARK_){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mult$tap_STAR_$arity$3 == null)))))){
return m.cljs$core$async$Mult$tap_STAR_$arity$3(m,ch,close_QMARK_);
} else {
return cljs$core$async$Mult$tap_STAR_$dyn_18374(m,ch,close_QMARK_);
}
});

var cljs$core$async$Mult$untap_STAR_$dyn_18378 = (function (m,ch){
var x__5498__auto__ = (((m == null))?null:m);
var m__5499__auto__ = (cljs.core.async.untap_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5499__auto__.call(null,m,ch));
} else {
var m__5497__auto__ = (cljs.core.async.untap_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5497__auto__.call(null,m,ch));
} else {
throw cljs.core.missing_protocol("Mult.untap*",m);
}
}
});
cljs.core.async.untap_STAR_ = (function cljs$core$async$untap_STAR_(m,ch){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mult$untap_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mult$untap_STAR_$arity$2(m,ch);
} else {
return cljs$core$async$Mult$untap_STAR_$dyn_18378(m,ch);
}
});

var cljs$core$async$Mult$untap_all_STAR_$dyn_18379 = (function (m){
var x__5498__auto__ = (((m == null))?null:m);
var m__5499__auto__ = (cljs.core.async.untap_all_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$1(m) : m__5499__auto__.call(null,m));
} else {
var m__5497__auto__ = (cljs.core.async.untap_all_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$1(m) : m__5497__auto__.call(null,m));
} else {
throw cljs.core.missing_protocol("Mult.untap-all*",m);
}
}
});
cljs.core.async.untap_all_STAR_ = (function cljs$core$async$untap_all_STAR_(m){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mult$untap_all_STAR_$arity$1 == null)))))){
return m.cljs$core$async$Mult$untap_all_STAR_$arity$1(m);
} else {
return cljs$core$async$Mult$untap_all_STAR_$dyn_18379(m);
}
});


/**
* @constructor
 * @implements {cljs.core.async.Mult}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.async.Mux}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async15742 = (function (ch,cs,meta15743){
this.ch = ch;
this.cs = cs;
this.meta15743 = meta15743;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async15742.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_15744,meta15743__$1){
var self__ = this;
var _15744__$1 = this;
return (new cljs.core.async.t_cljs$core$async15742(self__.ch,self__.cs,meta15743__$1));
}));

(cljs.core.async.t_cljs$core$async15742.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_15744){
var self__ = this;
var _15744__$1 = this;
return self__.meta15743;
}));

(cljs.core.async.t_cljs$core$async15742.prototype.cljs$core$async$Mux$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async15742.prototype.cljs$core$async$Mux$muxch_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.ch;
}));

(cljs.core.async.t_cljs$core$async15742.prototype.cljs$core$async$Mult$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async15742.prototype.cljs$core$async$Mult$tap_STAR_$arity$3 = (function (_,ch__$1,close_QMARK_){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(self__.cs,cljs.core.assoc,ch__$1,close_QMARK_);

return null;
}));

(cljs.core.async.t_cljs$core$async15742.prototype.cljs$core$async$Mult$untap_STAR_$arity$2 = (function (_,ch__$1){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(self__.cs,cljs.core.dissoc,ch__$1);

return null;
}));

(cljs.core.async.t_cljs$core$async15742.prototype.cljs$core$async$Mult$untap_all_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
cljs.core.reset_BANG_(self__.cs,cljs.core.PersistentArrayMap.EMPTY);

return null;
}));

(cljs.core.async.t_cljs$core$async15742.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"cs","cs",-117024463,null),new cljs.core.Symbol(null,"meta15743","meta15743",2063316107,null)], null);
}));

(cljs.core.async.t_cljs$core$async15742.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async15742.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async15742");

(cljs.core.async.t_cljs$core$async15742.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async15742");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async15742.
 */
cljs.core.async.__GT_t_cljs$core$async15742 = (function cljs$core$async$__GT_t_cljs$core$async15742(ch,cs,meta15743){
return (new cljs.core.async.t_cljs$core$async15742(ch,cs,meta15743));
});


/**
 * Creates and returns a mult(iple) of the supplied channel. Channels
 *   containing copies of the channel can be created with 'tap', and
 *   detached with 'untap'.
 * 
 *   Each item is distributed to all taps in parallel and synchronously,
 *   i.e. each tap must accept before the next item is distributed. Use
 *   buffering/windowing to prevent slow taps from holding up the mult.
 * 
 *   Items received when there are no taps get dropped.
 * 
 *   If a tap puts to a closed channel, it will be removed from the mult.
 */
cljs.core.async.mult = (function cljs$core$async$mult(ch){
var cs = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
var m = (new cljs.core.async.t_cljs$core$async15742(ch,cs,cljs.core.PersistentArrayMap.EMPTY));
var dchan = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
var dctr = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
var done = (function (_){
if((cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(dctr,cljs.core.dec) === (0))){
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(dchan,true);
} else {
return null;
}
});
var c__14503__auto___18397 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_15941){
var state_val_15942 = (state_15941[(1)]);
if((state_val_15942 === (7))){
var inst_15932 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_15947_18401 = state_15941__$1;
(statearr_15947_18401[(2)] = inst_15932);

(statearr_15947_18401[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (20))){
var inst_15821 = (state_15941[(7)]);
var inst_15836 = cljs.core.first(inst_15821);
var inst_15837 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_15836,(0),null);
var inst_15838 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_15836,(1),null);
var state_15941__$1 = (function (){var statearr_15954 = state_15941;
(statearr_15954[(8)] = inst_15837);

return statearr_15954;
})();
if(cljs.core.truth_(inst_15838)){
var statearr_15956_18402 = state_15941__$1;
(statearr_15956_18402[(1)] = (22));

} else {
var statearr_15957_18403 = state_15941__$1;
(statearr_15957_18403[(1)] = (23));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (27))){
var inst_15870 = (state_15941[(9)]);
var inst_15872 = (state_15941[(10)]);
var inst_15878 = (state_15941[(11)]);
var inst_15778 = (state_15941[(12)]);
var inst_15878__$1 = cljs.core._nth(inst_15870,inst_15872);
var inst_15880 = cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$3(inst_15878__$1,inst_15778,done);
var state_15941__$1 = (function (){var statearr_15960 = state_15941;
(statearr_15960[(11)] = inst_15878__$1);

return statearr_15960;
})();
if(cljs.core.truth_(inst_15880)){
var statearr_15961_18410 = state_15941__$1;
(statearr_15961_18410[(1)] = (30));

} else {
var statearr_15964_18411 = state_15941__$1;
(statearr_15964_18411[(1)] = (31));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (1))){
var state_15941__$1 = state_15941;
var statearr_15966_18412 = state_15941__$1;
(statearr_15966_18412[(2)] = null);

(statearr_15966_18412[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (24))){
var inst_15821 = (state_15941[(7)]);
var inst_15843 = (state_15941[(2)]);
var inst_15845 = cljs.core.next(inst_15821);
var inst_15792 = inst_15845;
var inst_15793 = null;
var inst_15794 = (0);
var inst_15795 = (0);
var state_15941__$1 = (function (){var statearr_15969 = state_15941;
(statearr_15969[(13)] = inst_15843);

(statearr_15969[(14)] = inst_15792);

(statearr_15969[(15)] = inst_15793);

(statearr_15969[(16)] = inst_15794);

(statearr_15969[(17)] = inst_15795);

return statearr_15969;
})();
var statearr_15973_18416 = state_15941__$1;
(statearr_15973_18416[(2)] = null);

(statearr_15973_18416[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (39))){
var state_15941__$1 = state_15941;
var statearr_15980_18417 = state_15941__$1;
(statearr_15980_18417[(2)] = null);

(statearr_15980_18417[(1)] = (41));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (4))){
var inst_15778 = (state_15941[(12)]);
var inst_15778__$1 = (state_15941[(2)]);
var inst_15780 = (inst_15778__$1 == null);
var state_15941__$1 = (function (){var statearr_15986 = state_15941;
(statearr_15986[(12)] = inst_15778__$1);

return statearr_15986;
})();
if(cljs.core.truth_(inst_15780)){
var statearr_15991_18419 = state_15941__$1;
(statearr_15991_18419[(1)] = (5));

} else {
var statearr_15994_18421 = state_15941__$1;
(statearr_15994_18421[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (15))){
var inst_15795 = (state_15941[(17)]);
var inst_15792 = (state_15941[(14)]);
var inst_15793 = (state_15941[(15)]);
var inst_15794 = (state_15941[(16)]);
var inst_15816 = (state_15941[(2)]);
var inst_15817 = (inst_15795 + (1));
var tmp15976 = inst_15792;
var tmp15977 = inst_15793;
var tmp15978 = inst_15794;
var inst_15792__$1 = tmp15976;
var inst_15793__$1 = tmp15977;
var inst_15794__$1 = tmp15978;
var inst_15795__$1 = inst_15817;
var state_15941__$1 = (function (){var statearr_15999 = state_15941;
(statearr_15999[(18)] = inst_15816);

(statearr_15999[(14)] = inst_15792__$1);

(statearr_15999[(15)] = inst_15793__$1);

(statearr_15999[(16)] = inst_15794__$1);

(statearr_15999[(17)] = inst_15795__$1);

return statearr_15999;
})();
var statearr_16001_18424 = state_15941__$1;
(statearr_16001_18424[(2)] = null);

(statearr_16001_18424[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (21))){
var inst_15848 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_16007_18427 = state_15941__$1;
(statearr_16007_18427[(2)] = inst_15848);

(statearr_16007_18427[(1)] = (18));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (31))){
var inst_15878 = (state_15941[(11)]);
var inst_15884 = m.cljs$core$async$Mult$untap_STAR_$arity$2(null,inst_15878);
var state_15941__$1 = state_15941;
var statearr_16015_18431 = state_15941__$1;
(statearr_16015_18431[(2)] = inst_15884);

(statearr_16015_18431[(1)] = (32));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (32))){
var inst_15872 = (state_15941[(10)]);
var inst_15869 = (state_15941[(19)]);
var inst_15870 = (state_15941[(9)]);
var inst_15871 = (state_15941[(20)]);
var inst_15886 = (state_15941[(2)]);
var inst_15888 = (inst_15872 + (1));
var tmp16003 = inst_15869;
var tmp16004 = inst_15870;
var tmp16005 = inst_15871;
var inst_15869__$1 = tmp16003;
var inst_15870__$1 = tmp16004;
var inst_15871__$1 = tmp16005;
var inst_15872__$1 = inst_15888;
var state_15941__$1 = (function (){var statearr_16017 = state_15941;
(statearr_16017[(21)] = inst_15886);

(statearr_16017[(19)] = inst_15869__$1);

(statearr_16017[(9)] = inst_15870__$1);

(statearr_16017[(20)] = inst_15871__$1);

(statearr_16017[(10)] = inst_15872__$1);

return statearr_16017;
})();
var statearr_16021_18439 = state_15941__$1;
(statearr_16021_18439[(2)] = null);

(statearr_16021_18439[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (40))){
var inst_15901 = (state_15941[(22)]);
var inst_15905 = m.cljs$core$async$Mult$untap_STAR_$arity$2(null,inst_15901);
var state_15941__$1 = state_15941;
var statearr_16026_18440 = state_15941__$1;
(statearr_16026_18440[(2)] = inst_15905);

(statearr_16026_18440[(1)] = (41));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (33))){
var inst_15891 = (state_15941[(23)]);
var inst_15893 = cljs.core.chunked_seq_QMARK_(inst_15891);
var state_15941__$1 = state_15941;
if(inst_15893){
var statearr_16030_18441 = state_15941__$1;
(statearr_16030_18441[(1)] = (36));

} else {
var statearr_16032_18442 = state_15941__$1;
(statearr_16032_18442[(1)] = (37));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (13))){
var inst_15810 = (state_15941[(24)]);
var inst_15813 = cljs.core.async.close_BANG_(inst_15810);
var state_15941__$1 = state_15941;
var statearr_16035_18444 = state_15941__$1;
(statearr_16035_18444[(2)] = inst_15813);

(statearr_16035_18444[(1)] = (15));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (22))){
var inst_15837 = (state_15941[(8)]);
var inst_15840 = cljs.core.async.close_BANG_(inst_15837);
var state_15941__$1 = state_15941;
var statearr_16037_18448 = state_15941__$1;
(statearr_16037_18448[(2)] = inst_15840);

(statearr_16037_18448[(1)] = (24));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (36))){
var inst_15891 = (state_15941[(23)]);
var inst_15896 = cljs.core.chunk_first(inst_15891);
var inst_15897 = cljs.core.chunk_rest(inst_15891);
var inst_15898 = cljs.core.count(inst_15896);
var inst_15869 = inst_15897;
var inst_15870 = inst_15896;
var inst_15871 = inst_15898;
var inst_15872 = (0);
var state_15941__$1 = (function (){var statearr_16043 = state_15941;
(statearr_16043[(19)] = inst_15869);

(statearr_16043[(9)] = inst_15870);

(statearr_16043[(20)] = inst_15871);

(statearr_16043[(10)] = inst_15872);

return statearr_16043;
})();
var statearr_16046_18456 = state_15941__$1;
(statearr_16046_18456[(2)] = null);

(statearr_16046_18456[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (41))){
var inst_15891 = (state_15941[(23)]);
var inst_15908 = (state_15941[(2)]);
var inst_15910 = cljs.core.next(inst_15891);
var inst_15869 = inst_15910;
var inst_15870 = null;
var inst_15871 = (0);
var inst_15872 = (0);
var state_15941__$1 = (function (){var statearr_16053 = state_15941;
(statearr_16053[(25)] = inst_15908);

(statearr_16053[(19)] = inst_15869);

(statearr_16053[(9)] = inst_15870);

(statearr_16053[(20)] = inst_15871);

(statearr_16053[(10)] = inst_15872);

return statearr_16053;
})();
var statearr_16055_18460 = state_15941__$1;
(statearr_16055_18460[(2)] = null);

(statearr_16055_18460[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (43))){
var state_15941__$1 = state_15941;
var statearr_16057_18461 = state_15941__$1;
(statearr_16057_18461[(2)] = null);

(statearr_16057_18461[(1)] = (44));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (29))){
var inst_15918 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_16067_18465 = state_15941__$1;
(statearr_16067_18465[(2)] = inst_15918);

(statearr_16067_18465[(1)] = (26));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (44))){
var inst_15929 = (state_15941[(2)]);
var state_15941__$1 = (function (){var statearr_16068 = state_15941;
(statearr_16068[(26)] = inst_15929);

return statearr_16068;
})();
var statearr_16069_18466 = state_15941__$1;
(statearr_16069_18466[(2)] = null);

(statearr_16069_18466[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (6))){
var inst_15859 = (state_15941[(27)]);
var inst_15858 = cljs.core.deref(cs);
var inst_15859__$1 = cljs.core.keys(inst_15858);
var inst_15860 = cljs.core.count(inst_15859__$1);
var inst_15861 = cljs.core.reset_BANG_(dctr,inst_15860);
var inst_15868 = cljs.core.seq(inst_15859__$1);
var inst_15869 = inst_15868;
var inst_15870 = null;
var inst_15871 = (0);
var inst_15872 = (0);
var state_15941__$1 = (function (){var statearr_16072 = state_15941;
(statearr_16072[(27)] = inst_15859__$1);

(statearr_16072[(28)] = inst_15861);

(statearr_16072[(19)] = inst_15869);

(statearr_16072[(9)] = inst_15870);

(statearr_16072[(20)] = inst_15871);

(statearr_16072[(10)] = inst_15872);

return statearr_16072;
})();
var statearr_16073_18474 = state_15941__$1;
(statearr_16073_18474[(2)] = null);

(statearr_16073_18474[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (28))){
var inst_15869 = (state_15941[(19)]);
var inst_15891 = (state_15941[(23)]);
var inst_15891__$1 = cljs.core.seq(inst_15869);
var state_15941__$1 = (function (){var statearr_16076 = state_15941;
(statearr_16076[(23)] = inst_15891__$1);

return statearr_16076;
})();
if(inst_15891__$1){
var statearr_16079_18475 = state_15941__$1;
(statearr_16079_18475[(1)] = (33));

} else {
var statearr_16081_18477 = state_15941__$1;
(statearr_16081_18477[(1)] = (34));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (25))){
var inst_15872 = (state_15941[(10)]);
var inst_15871 = (state_15941[(20)]);
var inst_15875 = (inst_15872 < inst_15871);
var inst_15876 = inst_15875;
var state_15941__$1 = state_15941;
if(cljs.core.truth_(inst_15876)){
var statearr_16083_18478 = state_15941__$1;
(statearr_16083_18478[(1)] = (27));

} else {
var statearr_16086_18479 = state_15941__$1;
(statearr_16086_18479[(1)] = (28));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (34))){
var state_15941__$1 = state_15941;
var statearr_16091_18481 = state_15941__$1;
(statearr_16091_18481[(2)] = null);

(statearr_16091_18481[(1)] = (35));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (17))){
var state_15941__$1 = state_15941;
var statearr_16093_18482 = state_15941__$1;
(statearr_16093_18482[(2)] = null);

(statearr_16093_18482[(1)] = (18));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (3))){
var inst_15934 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
return cljs.core.async.impl.ioc_helpers.return_chan(state_15941__$1,inst_15934);
} else {
if((state_val_15942 === (12))){
var inst_15853 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_16095_18483 = state_15941__$1;
(statearr_16095_18483[(2)] = inst_15853);

(statearr_16095_18483[(1)] = (9));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (2))){
var state_15941__$1 = state_15941;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15941__$1,(4),ch);
} else {
if((state_val_15942 === (23))){
var state_15941__$1 = state_15941;
var statearr_16099_18484 = state_15941__$1;
(statearr_16099_18484[(2)] = null);

(statearr_16099_18484[(1)] = (24));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (35))){
var inst_15916 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_16101_18485 = state_15941__$1;
(statearr_16101_18485[(2)] = inst_15916);

(statearr_16101_18485[(1)] = (29));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (19))){
var inst_15821 = (state_15941[(7)]);
var inst_15825 = cljs.core.chunk_first(inst_15821);
var inst_15826 = cljs.core.chunk_rest(inst_15821);
var inst_15828 = cljs.core.count(inst_15825);
var inst_15792 = inst_15826;
var inst_15793 = inst_15825;
var inst_15794 = inst_15828;
var inst_15795 = (0);
var state_15941__$1 = (function (){var statearr_16107 = state_15941;
(statearr_16107[(14)] = inst_15792);

(statearr_16107[(15)] = inst_15793);

(statearr_16107[(16)] = inst_15794);

(statearr_16107[(17)] = inst_15795);

return statearr_16107;
})();
var statearr_16110_18503 = state_15941__$1;
(statearr_16110_18503[(2)] = null);

(statearr_16110_18503[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (11))){
var inst_15792 = (state_15941[(14)]);
var inst_15821 = (state_15941[(7)]);
var inst_15821__$1 = cljs.core.seq(inst_15792);
var state_15941__$1 = (function (){var statearr_16114 = state_15941;
(statearr_16114[(7)] = inst_15821__$1);

return statearr_16114;
})();
if(inst_15821__$1){
var statearr_16116_18517 = state_15941__$1;
(statearr_16116_18517[(1)] = (16));

} else {
var statearr_16117_18523 = state_15941__$1;
(statearr_16117_18523[(1)] = (17));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (9))){
var inst_15855 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_16121_18528 = state_15941__$1;
(statearr_16121_18528[(2)] = inst_15855);

(statearr_16121_18528[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (5))){
var inst_15789 = cljs.core.deref(cs);
var inst_15791 = cljs.core.seq(inst_15789);
var inst_15792 = inst_15791;
var inst_15793 = null;
var inst_15794 = (0);
var inst_15795 = (0);
var state_15941__$1 = (function (){var statearr_16125 = state_15941;
(statearr_16125[(14)] = inst_15792);

(statearr_16125[(15)] = inst_15793);

(statearr_16125[(16)] = inst_15794);

(statearr_16125[(17)] = inst_15795);

return statearr_16125;
})();
var statearr_16127_18529 = state_15941__$1;
(statearr_16127_18529[(2)] = null);

(statearr_16127_18529[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (14))){
var state_15941__$1 = state_15941;
var statearr_16131_18530 = state_15941__$1;
(statearr_16131_18530[(2)] = null);

(statearr_16131_18530[(1)] = (15));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (45))){
var inst_15926 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_16137_18537 = state_15941__$1;
(statearr_16137_18537[(2)] = inst_15926);

(statearr_16137_18537[(1)] = (44));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (26))){
var inst_15859 = (state_15941[(27)]);
var inst_15920 = (state_15941[(2)]);
var inst_15922 = cljs.core.seq(inst_15859);
var state_15941__$1 = (function (){var statearr_16141 = state_15941;
(statearr_16141[(29)] = inst_15920);

return statearr_16141;
})();
if(inst_15922){
var statearr_16144_18542 = state_15941__$1;
(statearr_16144_18542[(1)] = (42));

} else {
var statearr_16147_18543 = state_15941__$1;
(statearr_16147_18543[(1)] = (43));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (16))){
var inst_15821 = (state_15941[(7)]);
var inst_15823 = cljs.core.chunked_seq_QMARK_(inst_15821);
var state_15941__$1 = state_15941;
if(inst_15823){
var statearr_16149_18544 = state_15941__$1;
(statearr_16149_18544[(1)] = (19));

} else {
var statearr_16150_18545 = state_15941__$1;
(statearr_16150_18545[(1)] = (20));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (38))){
var inst_15913 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_16153_18546 = state_15941__$1;
(statearr_16153_18546[(2)] = inst_15913);

(statearr_16153_18546[(1)] = (35));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (30))){
var state_15941__$1 = state_15941;
var statearr_16156_18548 = state_15941__$1;
(statearr_16156_18548[(2)] = null);

(statearr_16156_18548[(1)] = (32));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (10))){
var inst_15793 = (state_15941[(15)]);
var inst_15795 = (state_15941[(17)]);
var inst_15808 = cljs.core._nth(inst_15793,inst_15795);
var inst_15810 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_15808,(0),null);
var inst_15811 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_15808,(1),null);
var state_15941__$1 = (function (){var statearr_16162 = state_15941;
(statearr_16162[(24)] = inst_15810);

return statearr_16162;
})();
if(cljs.core.truth_(inst_15811)){
var statearr_16166_18552 = state_15941__$1;
(statearr_16166_18552[(1)] = (13));

} else {
var statearr_16168_18554 = state_15941__$1;
(statearr_16168_18554[(1)] = (14));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (18))){
var inst_15851 = (state_15941[(2)]);
var state_15941__$1 = state_15941;
var statearr_16170_18555 = state_15941__$1;
(statearr_16170_18555[(2)] = inst_15851);

(statearr_16170_18555[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (42))){
var state_15941__$1 = state_15941;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_15941__$1,(45),dchan);
} else {
if((state_val_15942 === (37))){
var inst_15891 = (state_15941[(23)]);
var inst_15901 = (state_15941[(22)]);
var inst_15778 = (state_15941[(12)]);
var inst_15901__$1 = cljs.core.first(inst_15891);
var inst_15902 = cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$3(inst_15901__$1,inst_15778,done);
var state_15941__$1 = (function (){var statearr_16175 = state_15941;
(statearr_16175[(22)] = inst_15901__$1);

return statearr_16175;
})();
if(cljs.core.truth_(inst_15902)){
var statearr_16176_18558 = state_15941__$1;
(statearr_16176_18558[(1)] = (39));

} else {
var statearr_16180_18559 = state_15941__$1;
(statearr_16180_18559[(1)] = (40));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_15942 === (8))){
var inst_15795 = (state_15941[(17)]);
var inst_15794 = (state_15941[(16)]);
var inst_15797 = (inst_15795 < inst_15794);
var inst_15798 = inst_15797;
var state_15941__$1 = state_15941;
if(cljs.core.truth_(inst_15798)){
var statearr_16182_18560 = state_15941__$1;
(statearr_16182_18560[(1)] = (10));

} else {
var statearr_16183_18561 = state_15941__$1;
(statearr_16183_18561[(1)] = (11));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$mult_$_state_machine__14014__auto__ = null;
var cljs$core$async$mult_$_state_machine__14014__auto____0 = (function (){
var statearr_16191 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_16191[(0)] = cljs$core$async$mult_$_state_machine__14014__auto__);

(statearr_16191[(1)] = (1));

return statearr_16191;
});
var cljs$core$async$mult_$_state_machine__14014__auto____1 = (function (state_15941){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_15941);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e16196){var ex__14017__auto__ = e16196;
var statearr_16197_18563 = state_15941;
(statearr_16197_18563[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_15941[(4)]))){
var statearr_16199_18564 = state_15941;
(statearr_16199_18564[(1)] = cljs.core.first((state_15941[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18565 = state_15941;
state_15941 = G__18565;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$mult_$_state_machine__14014__auto__ = function(state_15941){
switch(arguments.length){
case 0:
return cljs$core$async$mult_$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$mult_$_state_machine__14014__auto____1.call(this,state_15941);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$mult_$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$mult_$_state_machine__14014__auto____0;
cljs$core$async$mult_$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$mult_$_state_machine__14014__auto____1;
return cljs$core$async$mult_$_state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_16201 = f__14504__auto__();
(statearr_16201[(6)] = c__14503__auto___18397);

return statearr_16201;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return m;
});
/**
 * Copies the mult source onto the supplied channel.
 * 
 *   By default the channel will be closed when the source closes,
 *   but can be determined by the close? parameter.
 */
cljs.core.async.tap = (function cljs$core$async$tap(var_args){
var G__16216 = arguments.length;
switch (G__16216) {
case 2:
return cljs.core.async.tap.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.tap.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.tap.cljs$core$IFn$_invoke$arity$2 = (function (mult,ch){
return cljs.core.async.tap.cljs$core$IFn$_invoke$arity$3(mult,ch,true);
}));

(cljs.core.async.tap.cljs$core$IFn$_invoke$arity$3 = (function (mult,ch,close_QMARK_){
cljs.core.async.tap_STAR_(mult,ch,close_QMARK_);

return ch;
}));

(cljs.core.async.tap.cljs$lang$maxFixedArity = 3);

/**
 * Disconnects a target channel from a mult
 */
cljs.core.async.untap = (function cljs$core$async$untap(mult,ch){
return cljs.core.async.untap_STAR_(mult,ch);
});
/**
 * Disconnects all target channels from a mult
 */
cljs.core.async.untap_all = (function cljs$core$async$untap_all(mult){
return cljs.core.async.untap_all_STAR_(mult);
});

/**
 * @interface
 */
cljs.core.async.Mix = function(){};

var cljs$core$async$Mix$admix_STAR_$dyn_18569 = (function (m,ch){
var x__5498__auto__ = (((m == null))?null:m);
var m__5499__auto__ = (cljs.core.async.admix_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5499__auto__.call(null,m,ch));
} else {
var m__5497__auto__ = (cljs.core.async.admix_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5497__auto__.call(null,m,ch));
} else {
throw cljs.core.missing_protocol("Mix.admix*",m);
}
}
});
cljs.core.async.admix_STAR_ = (function cljs$core$async$admix_STAR_(m,ch){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$admix_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mix$admix_STAR_$arity$2(m,ch);
} else {
return cljs$core$async$Mix$admix_STAR_$dyn_18569(m,ch);
}
});

var cljs$core$async$Mix$unmix_STAR_$dyn_18570 = (function (m,ch){
var x__5498__auto__ = (((m == null))?null:m);
var m__5499__auto__ = (cljs.core.async.unmix_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5499__auto__.call(null,m,ch));
} else {
var m__5497__auto__ = (cljs.core.async.unmix_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$2(m,ch) : m__5497__auto__.call(null,m,ch));
} else {
throw cljs.core.missing_protocol("Mix.unmix*",m);
}
}
});
cljs.core.async.unmix_STAR_ = (function cljs$core$async$unmix_STAR_(m,ch){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$unmix_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mix$unmix_STAR_$arity$2(m,ch);
} else {
return cljs$core$async$Mix$unmix_STAR_$dyn_18570(m,ch);
}
});

var cljs$core$async$Mix$unmix_all_STAR_$dyn_18571 = (function (m){
var x__5498__auto__ = (((m == null))?null:m);
var m__5499__auto__ = (cljs.core.async.unmix_all_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$1(m) : m__5499__auto__.call(null,m));
} else {
var m__5497__auto__ = (cljs.core.async.unmix_all_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$1(m) : m__5497__auto__.call(null,m));
} else {
throw cljs.core.missing_protocol("Mix.unmix-all*",m);
}
}
});
cljs.core.async.unmix_all_STAR_ = (function cljs$core$async$unmix_all_STAR_(m){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$unmix_all_STAR_$arity$1 == null)))))){
return m.cljs$core$async$Mix$unmix_all_STAR_$arity$1(m);
} else {
return cljs$core$async$Mix$unmix_all_STAR_$dyn_18571(m);
}
});

var cljs$core$async$Mix$toggle_STAR_$dyn_18572 = (function (m,state_map){
var x__5498__auto__ = (((m == null))?null:m);
var m__5499__auto__ = (cljs.core.async.toggle_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$2(m,state_map) : m__5499__auto__.call(null,m,state_map));
} else {
var m__5497__auto__ = (cljs.core.async.toggle_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$2(m,state_map) : m__5497__auto__.call(null,m,state_map));
} else {
throw cljs.core.missing_protocol("Mix.toggle*",m);
}
}
});
cljs.core.async.toggle_STAR_ = (function cljs$core$async$toggle_STAR_(m,state_map){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$toggle_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mix$toggle_STAR_$arity$2(m,state_map);
} else {
return cljs$core$async$Mix$toggle_STAR_$dyn_18572(m,state_map);
}
});

var cljs$core$async$Mix$solo_mode_STAR_$dyn_18575 = (function (m,mode){
var x__5498__auto__ = (((m == null))?null:m);
var m__5499__auto__ = (cljs.core.async.solo_mode_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$2(m,mode) : m__5499__auto__.call(null,m,mode));
} else {
var m__5497__auto__ = (cljs.core.async.solo_mode_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$2(m,mode) : m__5497__auto__.call(null,m,mode));
} else {
throw cljs.core.missing_protocol("Mix.solo-mode*",m);
}
}
});
cljs.core.async.solo_mode_STAR_ = (function cljs$core$async$solo_mode_STAR_(m,mode){
if((((!((m == null)))) && ((!((m.cljs$core$async$Mix$solo_mode_STAR_$arity$2 == null)))))){
return m.cljs$core$async$Mix$solo_mode_STAR_$arity$2(m,mode);
} else {
return cljs$core$async$Mix$solo_mode_STAR_$dyn_18575(m,mode);
}
});

cljs.core.async.ioc_alts_BANG_ = (function cljs$core$async$ioc_alts_BANG_(var_args){
var args__5882__auto__ = [];
var len__5876__auto___18584 = arguments.length;
var i__5877__auto___18585 = (0);
while(true){
if((i__5877__auto___18585 < len__5876__auto___18584)){
args__5882__auto__.push((arguments[i__5877__auto___18585]));

var G__18586 = (i__5877__auto___18585 + (1));
i__5877__auto___18585 = G__18586;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((3) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((3)),(0),null)):null);
return cljs.core.async.ioc_alts_BANG_.cljs$core$IFn$_invoke$arity$variadic((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),argseq__5883__auto__);
});

(cljs.core.async.ioc_alts_BANG_.cljs$core$IFn$_invoke$arity$variadic = (function (state,cont_block,ports,p__16322){
var map__16323 = p__16322;
var map__16323__$1 = cljs.core.__destructure_map(map__16323);
var opts = map__16323__$1;
var statearr_16324_18588 = state;
(statearr_16324_18588[(1)] = cont_block);


var temp__5823__auto__ = cljs.core.async.do_alts((function (val){
var statearr_16327_18589 = state;
(statearr_16327_18589[(2)] = val);


return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state);
}),ports,opts);
if(cljs.core.truth_(temp__5823__auto__)){
var cb = temp__5823__auto__;
var statearr_16328_18590 = state;
(statearr_16328_18590[(2)] = cljs.core.deref(cb));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}));

(cljs.core.async.ioc_alts_BANG_.cljs$lang$maxFixedArity = (3));

/** @this {Function} */
(cljs.core.async.ioc_alts_BANG_.cljs$lang$applyTo = (function (seq16313){
var G__16314 = cljs.core.first(seq16313);
var seq16313__$1 = cljs.core.next(seq16313);
var G__16315 = cljs.core.first(seq16313__$1);
var seq16313__$2 = cljs.core.next(seq16313__$1);
var G__16316 = cljs.core.first(seq16313__$2);
var seq16313__$3 = cljs.core.next(seq16313__$2);
var self__5861__auto__ = this;
return self__5861__auto__.cljs$core$IFn$_invoke$arity$variadic(G__16314,G__16315,G__16316,seq16313__$3);
}));


/**
* @constructor
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.async.Mix}
 * @implements {cljs.core.async.Mux}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async16353 = (function (change,solo_mode,pick,cs,calc_state,out,changed,solo_modes,attrs,meta16354){
this.change = change;
this.solo_mode = solo_mode;
this.pick = pick;
this.cs = cs;
this.calc_state = calc_state;
this.out = out;
this.changed = changed;
this.solo_modes = solo_modes;
this.attrs = attrs;
this.meta16354 = meta16354;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_16355,meta16354__$1){
var self__ = this;
var _16355__$1 = this;
return (new cljs.core.async.t_cljs$core$async16353(self__.change,self__.solo_mode,self__.pick,self__.cs,self__.calc_state,self__.out,self__.changed,self__.solo_modes,self__.attrs,meta16354__$1));
}));

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_16355){
var self__ = this;
var _16355__$1 = this;
return self__.meta16354;
}));

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$async$Mux$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$async$Mux$muxch_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.out;
}));

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$async$Mix$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$async$Mix$admix_STAR_$arity$2 = (function (_,ch){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$4(self__.cs,cljs.core.assoc,ch,cljs.core.PersistentArrayMap.EMPTY);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null));
}));

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$async$Mix$unmix_STAR_$arity$2 = (function (_,ch){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(self__.cs,cljs.core.dissoc,ch);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null));
}));

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$async$Mix$unmix_all_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
cljs.core.reset_BANG_(self__.cs,cljs.core.PersistentArrayMap.EMPTY);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null));
}));

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$async$Mix$toggle_STAR_$arity$2 = (function (_,state_map){
var self__ = this;
var ___$1 = this;
cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(self__.cs,cljs.core.partial.cljs$core$IFn$_invoke$arity$2(cljs.core.merge_with,cljs.core.merge),state_map);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null));
}));

(cljs.core.async.t_cljs$core$async16353.prototype.cljs$core$async$Mix$solo_mode_STAR_$arity$2 = (function (_,mode){
var self__ = this;
var ___$1 = this;
if(cljs.core.truth_((self__.solo_modes.cljs$core$IFn$_invoke$arity$1 ? self__.solo_modes.cljs$core$IFn$_invoke$arity$1(mode) : self__.solo_modes.call(null,mode)))){
} else {
throw (new Error((""+"Assert failed: "+cljs.core.str.cljs$core$IFn$_invoke$arity$1((""+"mode must be one of: "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(self__.solo_modes)))+"\n"+"(solo-modes mode)")));
}

cljs.core.reset_BANG_(self__.solo_mode,mode);

return (self__.changed.cljs$core$IFn$_invoke$arity$0 ? self__.changed.cljs$core$IFn$_invoke$arity$0() : self__.changed.call(null));
}));

(cljs.core.async.t_cljs$core$async16353.getBasis = (function (){
return new cljs.core.PersistentVector(null, 10, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"change","change",477485025,null),new cljs.core.Symbol(null,"solo-mode","solo-mode",2031788074,null),new cljs.core.Symbol(null,"pick","pick",1300068175,null),new cljs.core.Symbol(null,"cs","cs",-117024463,null),new cljs.core.Symbol(null,"calc-state","calc-state",-349968968,null),new cljs.core.Symbol(null,"out","out",729986010,null),new cljs.core.Symbol(null,"changed","changed",-2083710852,null),new cljs.core.Symbol(null,"solo-modes","solo-modes",882180540,null),new cljs.core.Symbol(null,"attrs","attrs",-450137186,null),new cljs.core.Symbol(null,"meta16354","meta16354",1936764896,null)], null);
}));

(cljs.core.async.t_cljs$core$async16353.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async16353.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async16353");

(cljs.core.async.t_cljs$core$async16353.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async16353");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async16353.
 */
cljs.core.async.__GT_t_cljs$core$async16353 = (function cljs$core$async$__GT_t_cljs$core$async16353(change,solo_mode,pick,cs,calc_state,out,changed,solo_modes,attrs,meta16354){
return (new cljs.core.async.t_cljs$core$async16353(change,solo_mode,pick,cs,calc_state,out,changed,solo_modes,attrs,meta16354));
});


/**
 * Creates and returns a mix of one or more input channels which will
 *   be put on the supplied out channel. Input sources can be added to
 *   the mix with 'admix', and removed with 'unmix'. A mix supports
 *   soloing, muting and pausing multiple inputs atomically using
 *   'toggle', and can solo using either muting or pausing as determined
 *   by 'solo-mode'.
 * 
 *   Each channel can have zero or more boolean modes set via 'toggle':
 * 
 *   :solo - when true, only this (ond other soloed) channel(s) will appear
 *        in the mix output channel. :mute and :pause states of soloed
 *        channels are ignored. If solo-mode is :mute, non-soloed
 *        channels are muted, if :pause, non-soloed channels are
 *        paused.
 * 
 *   :mute - muted channels will have their contents consumed but not included in the mix
 *   :pause - paused channels will not have their contents consumed (and thus also not included in the mix)
 */
cljs.core.async.mix = (function cljs$core$async$mix(out){
var cs = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
var solo_modes = new cljs.core.PersistentHashSet(null, new cljs.core.PersistentArrayMap(null, 2, [new cljs.core.Keyword(null,"pause","pause",-2095325672),null,new cljs.core.Keyword(null,"mute","mute",1151223646),null], null), null);
var attrs = cljs.core.conj.cljs$core$IFn$_invoke$arity$2(solo_modes,new cljs.core.Keyword(null,"solo","solo",-316350075));
var solo_mode = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(new cljs.core.Keyword(null,"mute","mute",1151223646));
var change = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(cljs.core.async.sliding_buffer((1)));
var changed = (function (){
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(change,true);
});
var pick = (function (attr,chs){
return cljs.core.reduce_kv((function (ret,c,v){
if(cljs.core.truth_((attr.cljs$core$IFn$_invoke$arity$1 ? attr.cljs$core$IFn$_invoke$arity$1(v) : attr.call(null,v)))){
return cljs.core.conj.cljs$core$IFn$_invoke$arity$2(ret,c);
} else {
return ret;
}
}),cljs.core.PersistentHashSet.EMPTY,chs);
});
var calc_state = (function (){
var chs = cljs.core.deref(cs);
var mode = cljs.core.deref(solo_mode);
var solos = pick(new cljs.core.Keyword(null,"solo","solo",-316350075),chs);
var pauses = pick(new cljs.core.Keyword(null,"pause","pause",-2095325672),chs);
return new cljs.core.PersistentArrayMap(null, 3, [new cljs.core.Keyword(null,"solos","solos",1441458643),solos,new cljs.core.Keyword(null,"mutes","mutes",1068806309),pick(new cljs.core.Keyword(null,"mute","mute",1151223646),chs),new cljs.core.Keyword(null,"reads","reads",-1215067361),cljs.core.conj.cljs$core$IFn$_invoke$arity$2(((((cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(mode,new cljs.core.Keyword(null,"pause","pause",-2095325672))) && (cljs.core.seq(solos))))?cljs.core.vec(solos):cljs.core.vec(cljs.core.remove.cljs$core$IFn$_invoke$arity$2(pauses,cljs.core.keys(chs)))),change)], null);
});
var m = (new cljs.core.async.t_cljs$core$async16353(change,solo_mode,pick,cs,calc_state,out,changed,solo_modes,attrs,cljs.core.PersistentArrayMap.EMPTY));
var c__14503__auto___18604 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_16457){
var state_val_16458 = (state_16457[(1)]);
if((state_val_16458 === (7))){
var inst_16411 = (state_16457[(2)]);
var state_16457__$1 = state_16457;
if(cljs.core.truth_(inst_16411)){
var statearr_16464_18606 = state_16457__$1;
(statearr_16464_18606[(1)] = (8));

} else {
var statearr_16466_18607 = state_16457__$1;
(statearr_16466_18607[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (20))){
var inst_16401 = (state_16457[(7)]);
var state_16457__$1 = state_16457;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_16457__$1,(23),out,inst_16401);
} else {
if((state_val_16458 === (1))){
var inst_16376 = calc_state();
var inst_16377 = cljs.core.__destructure_map(inst_16376);
var inst_16378 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_16377,new cljs.core.Keyword(null,"solos","solos",1441458643));
var inst_16379 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_16377,new cljs.core.Keyword(null,"mutes","mutes",1068806309));
var inst_16384 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_16377,new cljs.core.Keyword(null,"reads","reads",-1215067361));
var inst_16385 = inst_16376;
var state_16457__$1 = (function (){var statearr_16467 = state_16457;
(statearr_16467[(8)] = inst_16378);

(statearr_16467[(9)] = inst_16379);

(statearr_16467[(10)] = inst_16384);

(statearr_16467[(11)] = inst_16385);

return statearr_16467;
})();
var statearr_16468_18612 = state_16457__$1;
(statearr_16468_18612[(2)] = null);

(statearr_16468_18612[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (24))){
var inst_16391 = (state_16457[(12)]);
var inst_16385 = inst_16391;
var state_16457__$1 = (function (){var statearr_16471 = state_16457;
(statearr_16471[(11)] = inst_16385);

return statearr_16471;
})();
var statearr_16473_18613 = state_16457__$1;
(statearr_16473_18613[(2)] = null);

(statearr_16473_18613[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (4))){
var inst_16401 = (state_16457[(7)]);
var inst_16406 = (state_16457[(13)]);
var inst_16400 = (state_16457[(2)]);
var inst_16401__$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_16400,(0),null);
var inst_16405 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_16400,(1),null);
var inst_16406__$1 = (inst_16401__$1 == null);
var state_16457__$1 = (function (){var statearr_16476 = state_16457;
(statearr_16476[(7)] = inst_16401__$1);

(statearr_16476[(14)] = inst_16405);

(statearr_16476[(13)] = inst_16406__$1);

return statearr_16476;
})();
if(cljs.core.truth_(inst_16406__$1)){
var statearr_16477_18620 = state_16457__$1;
(statearr_16477_18620[(1)] = (5));

} else {
var statearr_16478_18621 = state_16457__$1;
(statearr_16478_18621[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (15))){
var inst_16392 = (state_16457[(15)]);
var inst_16428 = (state_16457[(16)]);
var inst_16428__$1 = cljs.core.empty_QMARK_(inst_16392);
var state_16457__$1 = (function (){var statearr_16479 = state_16457;
(statearr_16479[(16)] = inst_16428__$1);

return statearr_16479;
})();
if(inst_16428__$1){
var statearr_16480_18622 = state_16457__$1;
(statearr_16480_18622[(1)] = (17));

} else {
var statearr_16481_18623 = state_16457__$1;
(statearr_16481_18623[(1)] = (18));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (21))){
var inst_16391 = (state_16457[(12)]);
var inst_16385 = inst_16391;
var state_16457__$1 = (function (){var statearr_16482 = state_16457;
(statearr_16482[(11)] = inst_16385);

return statearr_16482;
})();
var statearr_16483_18624 = state_16457__$1;
(statearr_16483_18624[(2)] = null);

(statearr_16483_18624[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (13))){
var inst_16418 = (state_16457[(2)]);
var inst_16419 = calc_state();
var inst_16385 = inst_16419;
var state_16457__$1 = (function (){var statearr_16488 = state_16457;
(statearr_16488[(17)] = inst_16418);

(statearr_16488[(11)] = inst_16385);

return statearr_16488;
})();
var statearr_16490_18625 = state_16457__$1;
(statearr_16490_18625[(2)] = null);

(statearr_16490_18625[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (22))){
var inst_16449 = (state_16457[(2)]);
var state_16457__$1 = state_16457;
var statearr_16494_18626 = state_16457__$1;
(statearr_16494_18626[(2)] = inst_16449);

(statearr_16494_18626[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (6))){
var inst_16405 = (state_16457[(14)]);
var inst_16409 = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(inst_16405,change);
var state_16457__$1 = state_16457;
var statearr_16497_18628 = state_16457__$1;
(statearr_16497_18628[(2)] = inst_16409);

(statearr_16497_18628[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (25))){
var state_16457__$1 = state_16457;
var statearr_16498_18629 = state_16457__$1;
(statearr_16498_18629[(2)] = null);

(statearr_16498_18629[(1)] = (26));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (17))){
var inst_16393 = (state_16457[(18)]);
var inst_16405 = (state_16457[(14)]);
var inst_16430 = (inst_16393.cljs$core$IFn$_invoke$arity$1 ? inst_16393.cljs$core$IFn$_invoke$arity$1(inst_16405) : inst_16393.call(null,inst_16405));
var inst_16431 = cljs.core.not(inst_16430);
var state_16457__$1 = state_16457;
var statearr_16502_18635 = state_16457__$1;
(statearr_16502_18635[(2)] = inst_16431);

(statearr_16502_18635[(1)] = (19));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (3))){
var inst_16453 = (state_16457[(2)]);
var state_16457__$1 = state_16457;
return cljs.core.async.impl.ioc_helpers.return_chan(state_16457__$1,inst_16453);
} else {
if((state_val_16458 === (12))){
var state_16457__$1 = state_16457;
var statearr_16504_18645 = state_16457__$1;
(statearr_16504_18645[(2)] = null);

(statearr_16504_18645[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (2))){
var inst_16385 = (state_16457[(11)]);
var inst_16391 = (state_16457[(12)]);
var inst_16391__$1 = cljs.core.__destructure_map(inst_16385);
var inst_16392 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_16391__$1,new cljs.core.Keyword(null,"solos","solos",1441458643));
var inst_16393 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_16391__$1,new cljs.core.Keyword(null,"mutes","mutes",1068806309));
var inst_16394 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_16391__$1,new cljs.core.Keyword(null,"reads","reads",-1215067361));
var state_16457__$1 = (function (){var statearr_16513 = state_16457;
(statearr_16513[(12)] = inst_16391__$1);

(statearr_16513[(15)] = inst_16392);

(statearr_16513[(18)] = inst_16393);

return statearr_16513;
})();
return cljs.core.async.ioc_alts_BANG_(state_16457__$1,(4),inst_16394);
} else {
if((state_val_16458 === (23))){
var inst_16439 = (state_16457[(2)]);
var state_16457__$1 = state_16457;
if(cljs.core.truth_(inst_16439)){
var statearr_16518_18646 = state_16457__$1;
(statearr_16518_18646[(1)] = (24));

} else {
var statearr_16519_18647 = state_16457__$1;
(statearr_16519_18647[(1)] = (25));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (19))){
var inst_16434 = (state_16457[(2)]);
var state_16457__$1 = state_16457;
var statearr_16524_18648 = state_16457__$1;
(statearr_16524_18648[(2)] = inst_16434);

(statearr_16524_18648[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (11))){
var inst_16405 = (state_16457[(14)]);
var inst_16415 = cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(cs,cljs.core.dissoc,inst_16405);
var state_16457__$1 = state_16457;
var statearr_16527_18649 = state_16457__$1;
(statearr_16527_18649[(2)] = inst_16415);

(statearr_16527_18649[(1)] = (13));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (9))){
var inst_16392 = (state_16457[(15)]);
var inst_16405 = (state_16457[(14)]);
var inst_16422 = (state_16457[(19)]);
var inst_16422__$1 = (inst_16392.cljs$core$IFn$_invoke$arity$1 ? inst_16392.cljs$core$IFn$_invoke$arity$1(inst_16405) : inst_16392.call(null,inst_16405));
var state_16457__$1 = (function (){var statearr_16530 = state_16457;
(statearr_16530[(19)] = inst_16422__$1);

return statearr_16530;
})();
if(cljs.core.truth_(inst_16422__$1)){
var statearr_16531_18652 = state_16457__$1;
(statearr_16531_18652[(1)] = (14));

} else {
var statearr_16533_18653 = state_16457__$1;
(statearr_16533_18653[(1)] = (15));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (5))){
var inst_16406 = (state_16457[(13)]);
var state_16457__$1 = state_16457;
var statearr_16534_18655 = state_16457__$1;
(statearr_16534_18655[(2)] = inst_16406);

(statearr_16534_18655[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (14))){
var inst_16422 = (state_16457[(19)]);
var state_16457__$1 = state_16457;
var statearr_16536_18656 = state_16457__$1;
(statearr_16536_18656[(2)] = inst_16422);

(statearr_16536_18656[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (26))){
var inst_16445 = (state_16457[(2)]);
var state_16457__$1 = state_16457;
var statearr_16537_18658 = state_16457__$1;
(statearr_16537_18658[(2)] = inst_16445);

(statearr_16537_18658[(1)] = (22));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (16))){
var inst_16436 = (state_16457[(2)]);
var state_16457__$1 = state_16457;
if(cljs.core.truth_(inst_16436)){
var statearr_16542_18659 = state_16457__$1;
(statearr_16542_18659[(1)] = (20));

} else {
var statearr_16543_18660 = state_16457__$1;
(statearr_16543_18660[(1)] = (21));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (10))){
var inst_16451 = (state_16457[(2)]);
var state_16457__$1 = state_16457;
var statearr_16545_18665 = state_16457__$1;
(statearr_16545_18665[(2)] = inst_16451);

(statearr_16545_18665[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (18))){
var inst_16428 = (state_16457[(16)]);
var state_16457__$1 = state_16457;
var statearr_16546_18667 = state_16457__$1;
(statearr_16546_18667[(2)] = inst_16428);

(statearr_16546_18667[(1)] = (19));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16458 === (8))){
var inst_16401 = (state_16457[(7)]);
var inst_16413 = (inst_16401 == null);
var state_16457__$1 = state_16457;
if(cljs.core.truth_(inst_16413)){
var statearr_16548_18670 = state_16457__$1;
(statearr_16548_18670[(1)] = (11));

} else {
var statearr_16549_18671 = state_16457__$1;
(statearr_16549_18671[(1)] = (12));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$mix_$_state_machine__14014__auto__ = null;
var cljs$core$async$mix_$_state_machine__14014__auto____0 = (function (){
var statearr_16552 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_16552[(0)] = cljs$core$async$mix_$_state_machine__14014__auto__);

(statearr_16552[(1)] = (1));

return statearr_16552;
});
var cljs$core$async$mix_$_state_machine__14014__auto____1 = (function (state_16457){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_16457);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e16553){var ex__14017__auto__ = e16553;
var statearr_16556_18675 = state_16457;
(statearr_16556_18675[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_16457[(4)]))){
var statearr_16557_18676 = state_16457;
(statearr_16557_18676[(1)] = cljs.core.first((state_16457[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18677 = state_16457;
state_16457 = G__18677;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$mix_$_state_machine__14014__auto__ = function(state_16457){
switch(arguments.length){
case 0:
return cljs$core$async$mix_$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$mix_$_state_machine__14014__auto____1.call(this,state_16457);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$mix_$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$mix_$_state_machine__14014__auto____0;
cljs$core$async$mix_$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$mix_$_state_machine__14014__auto____1;
return cljs$core$async$mix_$_state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_16559 = f__14504__auto__();
(statearr_16559[(6)] = c__14503__auto___18604);

return statearr_16559;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return m;
});
/**
 * Adds ch as an input to the mix
 */
cljs.core.async.admix = (function cljs$core$async$admix(mix,ch){
return cljs.core.async.admix_STAR_(mix,ch);
});
/**
 * Removes ch as an input to the mix
 */
cljs.core.async.unmix = (function cljs$core$async$unmix(mix,ch){
return cljs.core.async.unmix_STAR_(mix,ch);
});
/**
 * removes all inputs from the mix
 */
cljs.core.async.unmix_all = (function cljs$core$async$unmix_all(mix){
return cljs.core.async.unmix_all_STAR_(mix);
});
/**
 * Atomically sets the state(s) of one or more channels in a mix. The
 *   state map is a map of channels -> channel-state-map. A
 *   channel-state-map is a map of attrs -> boolean, where attr is one or
 *   more of :mute, :pause or :solo. Any states supplied are merged with
 *   the current state.
 * 
 *   Note that channels can be added to a mix via toggle, which can be
 *   used to add channels in a particular (e.g. paused) state.
 */
cljs.core.async.toggle = (function cljs$core$async$toggle(mix,state_map){
return cljs.core.async.toggle_STAR_(mix,state_map);
});
/**
 * Sets the solo mode of the mix. mode must be one of :mute or :pause
 */
cljs.core.async.solo_mode = (function cljs$core$async$solo_mode(mix,mode){
return cljs.core.async.solo_mode_STAR_(mix,mode);
});

/**
 * @interface
 */
cljs.core.async.Pub = function(){};

var cljs$core$async$Pub$sub_STAR_$dyn_18683 = (function (p,v,ch,close_QMARK_){
var x__5498__auto__ = (((p == null))?null:p);
var m__5499__auto__ = (cljs.core.async.sub_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$4 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$4(p,v,ch,close_QMARK_) : m__5499__auto__.call(null,p,v,ch,close_QMARK_));
} else {
var m__5497__auto__ = (cljs.core.async.sub_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$4 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$4(p,v,ch,close_QMARK_) : m__5497__auto__.call(null,p,v,ch,close_QMARK_));
} else {
throw cljs.core.missing_protocol("Pub.sub*",p);
}
}
});
cljs.core.async.sub_STAR_ = (function cljs$core$async$sub_STAR_(p,v,ch,close_QMARK_){
if((((!((p == null)))) && ((!((p.cljs$core$async$Pub$sub_STAR_$arity$4 == null)))))){
return p.cljs$core$async$Pub$sub_STAR_$arity$4(p,v,ch,close_QMARK_);
} else {
return cljs$core$async$Pub$sub_STAR_$dyn_18683(p,v,ch,close_QMARK_);
}
});

var cljs$core$async$Pub$unsub_STAR_$dyn_18685 = (function (p,v,ch){
var x__5498__auto__ = (((p == null))?null:p);
var m__5499__auto__ = (cljs.core.async.unsub_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$3 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$3(p,v,ch) : m__5499__auto__.call(null,p,v,ch));
} else {
var m__5497__auto__ = (cljs.core.async.unsub_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$3 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$3(p,v,ch) : m__5497__auto__.call(null,p,v,ch));
} else {
throw cljs.core.missing_protocol("Pub.unsub*",p);
}
}
});
cljs.core.async.unsub_STAR_ = (function cljs$core$async$unsub_STAR_(p,v,ch){
if((((!((p == null)))) && ((!((p.cljs$core$async$Pub$unsub_STAR_$arity$3 == null)))))){
return p.cljs$core$async$Pub$unsub_STAR_$arity$3(p,v,ch);
} else {
return cljs$core$async$Pub$unsub_STAR_$dyn_18685(p,v,ch);
}
});

var cljs$core$async$Pub$unsub_all_STAR_$dyn_18690 = (function() {
var G__18691 = null;
var G__18691__1 = (function (p){
var x__5498__auto__ = (((p == null))?null:p);
var m__5499__auto__ = (cljs.core.async.unsub_all_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$1(p) : m__5499__auto__.call(null,p));
} else {
var m__5497__auto__ = (cljs.core.async.unsub_all_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$1 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$1(p) : m__5497__auto__.call(null,p));
} else {
throw cljs.core.missing_protocol("Pub.unsub-all*",p);
}
}
});
var G__18691__2 = (function (p,v){
var x__5498__auto__ = (((p == null))?null:p);
var m__5499__auto__ = (cljs.core.async.unsub_all_STAR_[goog.typeOf(x__5498__auto__)]);
if((!((m__5499__auto__ == null)))){
return (m__5499__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5499__auto__.cljs$core$IFn$_invoke$arity$2(p,v) : m__5499__auto__.call(null,p,v));
} else {
var m__5497__auto__ = (cljs.core.async.unsub_all_STAR_["_"]);
if((!((m__5497__auto__ == null)))){
return (m__5497__auto__.cljs$core$IFn$_invoke$arity$2 ? m__5497__auto__.cljs$core$IFn$_invoke$arity$2(p,v) : m__5497__auto__.call(null,p,v));
} else {
throw cljs.core.missing_protocol("Pub.unsub-all*",p);
}
}
});
G__18691 = function(p,v){
switch(arguments.length){
case 1:
return G__18691__1.call(this,p);
case 2:
return G__18691__2.call(this,p,v);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
G__18691.cljs$core$IFn$_invoke$arity$1 = G__18691__1;
G__18691.cljs$core$IFn$_invoke$arity$2 = G__18691__2;
return G__18691;
})()
;
cljs.core.async.unsub_all_STAR_ = (function cljs$core$async$unsub_all_STAR_(var_args){
var G__16580 = arguments.length;
switch (G__16580) {
case 1:
return cljs.core.async.unsub_all_STAR_.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.unsub_all_STAR_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.unsub_all_STAR_.cljs$core$IFn$_invoke$arity$1 = (function (p){
if((((!((p == null)))) && ((!((p.cljs$core$async$Pub$unsub_all_STAR_$arity$1 == null)))))){
return p.cljs$core$async$Pub$unsub_all_STAR_$arity$1(p);
} else {
return cljs$core$async$Pub$unsub_all_STAR_$dyn_18690(p);
}
}));

(cljs.core.async.unsub_all_STAR_.cljs$core$IFn$_invoke$arity$2 = (function (p,v){
if((((!((p == null)))) && ((!((p.cljs$core$async$Pub$unsub_all_STAR_$arity$2 == null)))))){
return p.cljs$core$async$Pub$unsub_all_STAR_$arity$2(p,v);
} else {
return cljs$core$async$Pub$unsub_all_STAR_$dyn_18690(p,v);
}
}));

(cljs.core.async.unsub_all_STAR_.cljs$lang$maxFixedArity = 2);



/**
* @constructor
 * @implements {cljs.core.async.Pub}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.async.Mux}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async16594 = (function (ch,topic_fn,buf_fn,mults,ensure_mult,meta16595){
this.ch = ch;
this.topic_fn = topic_fn;
this.buf_fn = buf_fn;
this.mults = mults;
this.ensure_mult = ensure_mult;
this.meta16595 = meta16595;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_16596,meta16595__$1){
var self__ = this;
var _16596__$1 = this;
return (new cljs.core.async.t_cljs$core$async16594(self__.ch,self__.topic_fn,self__.buf_fn,self__.mults,self__.ensure_mult,meta16595__$1));
}));

(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_16596){
var self__ = this;
var _16596__$1 = this;
return self__.meta16595;
}));

(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$async$Mux$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$async$Mux$muxch_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return self__.ch;
}));

(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$async$Pub$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$async$Pub$sub_STAR_$arity$4 = (function (p,topic,ch__$1,close_QMARK_){
var self__ = this;
var p__$1 = this;
var m = (self__.ensure_mult.cljs$core$IFn$_invoke$arity$1 ? self__.ensure_mult.cljs$core$IFn$_invoke$arity$1(topic) : self__.ensure_mult.call(null,topic));
return cljs.core.async.tap.cljs$core$IFn$_invoke$arity$3(m,ch__$1,close_QMARK_);
}));

(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$async$Pub$unsub_STAR_$arity$3 = (function (p,topic,ch__$1){
var self__ = this;
var p__$1 = this;
var temp__5823__auto__ = cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(self__.mults),topic);
if(cljs.core.truth_(temp__5823__auto__)){
var m = temp__5823__auto__;
return cljs.core.async.untap(m,ch__$1);
} else {
return null;
}
}));

(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$async$Pub$unsub_all_STAR_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.reset_BANG_(self__.mults,cljs.core.PersistentArrayMap.EMPTY);
}));

(cljs.core.async.t_cljs$core$async16594.prototype.cljs$core$async$Pub$unsub_all_STAR_$arity$2 = (function (_,topic){
var self__ = this;
var ___$1 = this;
return cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(self__.mults,cljs.core.dissoc,topic);
}));

(cljs.core.async.t_cljs$core$async16594.getBasis = (function (){
return new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"topic-fn","topic-fn",-862449736,null),new cljs.core.Symbol(null,"buf-fn","buf-fn",-1200281591,null),new cljs.core.Symbol(null,"mults","mults",-461114485,null),new cljs.core.Symbol(null,"ensure-mult","ensure-mult",1796584816,null),new cljs.core.Symbol(null,"meta16595","meta16595",-1081954676,null)], null);
}));

(cljs.core.async.t_cljs$core$async16594.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async16594.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async16594");

(cljs.core.async.t_cljs$core$async16594.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async16594");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async16594.
 */
cljs.core.async.__GT_t_cljs$core$async16594 = (function cljs$core$async$__GT_t_cljs$core$async16594(ch,topic_fn,buf_fn,mults,ensure_mult,meta16595){
return (new cljs.core.async.t_cljs$core$async16594(ch,topic_fn,buf_fn,mults,ensure_mult,meta16595));
});


/**
 * Creates and returns a pub(lication) of the supplied channel,
 *   partitioned into topics by the topic-fn. topic-fn will be applied to
 *   each value on the channel and the result will determine the 'topic'
 *   on which that value will be put. Channels can be subscribed to
 *   receive copies of topics using 'sub', and unsubscribed using
 *   'unsub'. Each topic will be handled by an internal mult on a
 *   dedicated channel. By default these internal channels are
 *   unbuffered, but a buf-fn can be supplied which, given a topic,
 *   creates a buffer with desired properties.
 * 
 *   Each item is distributed to all subs in parallel and synchronously,
 *   i.e. each sub must accept before the next item is distributed. Use
 *   buffering/windowing to prevent slow subs from holding up the pub.
 * 
 *   Items received when there are no matching subs get dropped.
 * 
 *   Note that if buf-fns are used then each topic is handled
 *   asynchronously, i.e. if a channel is subscribed to more than one
 *   topic it should not expect them to be interleaved identically with
 *   the source.
 */
cljs.core.async.pub = (function cljs$core$async$pub(var_args){
var G__16589 = arguments.length;
switch (G__16589) {
case 2:
return cljs.core.async.pub.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.pub.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.pub.cljs$core$IFn$_invoke$arity$2 = (function (ch,topic_fn){
return cljs.core.async.pub.cljs$core$IFn$_invoke$arity$3(ch,topic_fn,cljs.core.constantly(null));
}));

(cljs.core.async.pub.cljs$core$IFn$_invoke$arity$3 = (function (ch,topic_fn,buf_fn){
var mults = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(cljs.core.PersistentArrayMap.EMPTY);
var ensure_mult = (function (topic){
var or__5142__auto__ = cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(mults),topic);
if(cljs.core.truth_(or__5142__auto__)){
return or__5142__auto__;
} else {
return cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(mults,(function (p1__16587_SHARP_){
if(cljs.core.truth_((p1__16587_SHARP_.cljs$core$IFn$_invoke$arity$1 ? p1__16587_SHARP_.cljs$core$IFn$_invoke$arity$1(topic) : p1__16587_SHARP_.call(null,topic)))){
return p1__16587_SHARP_;
} else {
return cljs.core.assoc.cljs$core$IFn$_invoke$arity$3(p1__16587_SHARP_,topic,cljs.core.async.mult(cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((buf_fn.cljs$core$IFn$_invoke$arity$1 ? buf_fn.cljs$core$IFn$_invoke$arity$1(topic) : buf_fn.call(null,topic)))));
}
})),topic);
}
});
var p = (new cljs.core.async.t_cljs$core$async16594(ch,topic_fn,buf_fn,mults,ensure_mult,cljs.core.PersistentArrayMap.EMPTY));
var c__14503__auto___18732 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_16691){
var state_val_16692 = (state_16691[(1)]);
if((state_val_16692 === (7))){
var inst_16687 = (state_16691[(2)]);
var state_16691__$1 = state_16691;
var statearr_16693_18740 = state_16691__$1;
(statearr_16693_18740[(2)] = inst_16687);

(statearr_16693_18740[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (20))){
var state_16691__$1 = state_16691;
var statearr_16698_18741 = state_16691__$1;
(statearr_16698_18741[(2)] = null);

(statearr_16698_18741[(1)] = (21));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (1))){
var state_16691__$1 = state_16691;
var statearr_16699_18743 = state_16691__$1;
(statearr_16699_18743[(2)] = null);

(statearr_16699_18743[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (24))){
var inst_16666 = (state_16691[(7)]);
var inst_16677 = cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$3(mults,cljs.core.dissoc,inst_16666);
var state_16691__$1 = state_16691;
var statearr_16702_18748 = state_16691__$1;
(statearr_16702_18748[(2)] = inst_16677);

(statearr_16702_18748[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (4))){
var inst_16614 = (state_16691[(8)]);
var inst_16614__$1 = (state_16691[(2)]);
var inst_16615 = (inst_16614__$1 == null);
var state_16691__$1 = (function (){var statearr_16703 = state_16691;
(statearr_16703[(8)] = inst_16614__$1);

return statearr_16703;
})();
if(cljs.core.truth_(inst_16615)){
var statearr_16704_18754 = state_16691__$1;
(statearr_16704_18754[(1)] = (5));

} else {
var statearr_16705_18755 = state_16691__$1;
(statearr_16705_18755[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (15))){
var inst_16660 = (state_16691[(2)]);
var state_16691__$1 = state_16691;
var statearr_16707_18756 = state_16691__$1;
(statearr_16707_18756[(2)] = inst_16660);

(statearr_16707_18756[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (21))){
var inst_16682 = (state_16691[(2)]);
var state_16691__$1 = (function (){var statearr_16708 = state_16691;
(statearr_16708[(9)] = inst_16682);

return statearr_16708;
})();
var statearr_16709_18758 = state_16691__$1;
(statearr_16709_18758[(2)] = null);

(statearr_16709_18758[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (13))){
var inst_16640 = (state_16691[(10)]);
var inst_16642 = cljs.core.chunked_seq_QMARK_(inst_16640);
var state_16691__$1 = state_16691;
if(inst_16642){
var statearr_16728_18761 = state_16691__$1;
(statearr_16728_18761[(1)] = (16));

} else {
var statearr_16736_18764 = state_16691__$1;
(statearr_16736_18764[(1)] = (17));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (22))){
var inst_16674 = (state_16691[(2)]);
var state_16691__$1 = state_16691;
if(cljs.core.truth_(inst_16674)){
var statearr_16737_18768 = state_16691__$1;
(statearr_16737_18768[(1)] = (23));

} else {
var statearr_16738_18769 = state_16691__$1;
(statearr_16738_18769[(1)] = (24));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (6))){
var inst_16614 = (state_16691[(8)]);
var inst_16666 = (state_16691[(7)]);
var inst_16668 = (state_16691[(11)]);
var inst_16666__$1 = (topic_fn.cljs$core$IFn$_invoke$arity$1 ? topic_fn.cljs$core$IFn$_invoke$arity$1(inst_16614) : topic_fn.call(null,inst_16614));
var inst_16667 = cljs.core.deref(mults);
var inst_16668__$1 = cljs.core.get.cljs$core$IFn$_invoke$arity$2(inst_16667,inst_16666__$1);
var state_16691__$1 = (function (){var statearr_16740 = state_16691;
(statearr_16740[(7)] = inst_16666__$1);

(statearr_16740[(11)] = inst_16668__$1);

return statearr_16740;
})();
if(cljs.core.truth_(inst_16668__$1)){
var statearr_16742_18772 = state_16691__$1;
(statearr_16742_18772[(1)] = (19));

} else {
var statearr_16746_18773 = state_16691__$1;
(statearr_16746_18773[(1)] = (20));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (25))){
var inst_16679 = (state_16691[(2)]);
var state_16691__$1 = state_16691;
var statearr_16749_18774 = state_16691__$1;
(statearr_16749_18774[(2)] = inst_16679);

(statearr_16749_18774[(1)] = (21));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (17))){
var inst_16640 = (state_16691[(10)]);
var inst_16650 = cljs.core.first(inst_16640);
var inst_16651 = cljs.core.async.muxch_STAR_(inst_16650);
var inst_16652 = cljs.core.async.close_BANG_(inst_16651);
var inst_16654 = cljs.core.next(inst_16640);
var inst_16624 = inst_16654;
var inst_16625 = null;
var inst_16626 = (0);
var inst_16627 = (0);
var state_16691__$1 = (function (){var statearr_16750 = state_16691;
(statearr_16750[(12)] = inst_16652);

(statearr_16750[(13)] = inst_16624);

(statearr_16750[(14)] = inst_16625);

(statearr_16750[(15)] = inst_16626);

(statearr_16750[(16)] = inst_16627);

return statearr_16750;
})();
var statearr_16751_18775 = state_16691__$1;
(statearr_16751_18775[(2)] = null);

(statearr_16751_18775[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (3))){
var inst_16689 = (state_16691[(2)]);
var state_16691__$1 = state_16691;
return cljs.core.async.impl.ioc_helpers.return_chan(state_16691__$1,inst_16689);
} else {
if((state_val_16692 === (12))){
var inst_16662 = (state_16691[(2)]);
var state_16691__$1 = state_16691;
var statearr_16760_18781 = state_16691__$1;
(statearr_16760_18781[(2)] = inst_16662);

(statearr_16760_18781[(1)] = (9));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (2))){
var state_16691__$1 = state_16691;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_16691__$1,(4),ch);
} else {
if((state_val_16692 === (23))){
var state_16691__$1 = state_16691;
var statearr_16763_18790 = state_16691__$1;
(statearr_16763_18790[(2)] = null);

(statearr_16763_18790[(1)] = (25));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (19))){
var inst_16668 = (state_16691[(11)]);
var inst_16614 = (state_16691[(8)]);
var inst_16670 = cljs.core.async.muxch_STAR_(inst_16668);
var state_16691__$1 = state_16691;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_16691__$1,(22),inst_16670,inst_16614);
} else {
if((state_val_16692 === (11))){
var inst_16624 = (state_16691[(13)]);
var inst_16640 = (state_16691[(10)]);
var inst_16640__$1 = cljs.core.seq(inst_16624);
var state_16691__$1 = (function (){var statearr_16765 = state_16691;
(statearr_16765[(10)] = inst_16640__$1);

return statearr_16765;
})();
if(inst_16640__$1){
var statearr_16766_18792 = state_16691__$1;
(statearr_16766_18792[(1)] = (13));

} else {
var statearr_16767_18793 = state_16691__$1;
(statearr_16767_18793[(1)] = (14));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (9))){
var inst_16664 = (state_16691[(2)]);
var state_16691__$1 = state_16691;
var statearr_16768_18795 = state_16691__$1;
(statearr_16768_18795[(2)] = inst_16664);

(statearr_16768_18795[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (5))){
var inst_16621 = cljs.core.deref(mults);
var inst_16622 = cljs.core.vals(inst_16621);
var inst_16623 = cljs.core.seq(inst_16622);
var inst_16624 = inst_16623;
var inst_16625 = null;
var inst_16626 = (0);
var inst_16627 = (0);
var state_16691__$1 = (function (){var statearr_16771 = state_16691;
(statearr_16771[(13)] = inst_16624);

(statearr_16771[(14)] = inst_16625);

(statearr_16771[(15)] = inst_16626);

(statearr_16771[(16)] = inst_16627);

return statearr_16771;
})();
var statearr_16785_18798 = state_16691__$1;
(statearr_16785_18798[(2)] = null);

(statearr_16785_18798[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (14))){
var state_16691__$1 = state_16691;
var statearr_16791_18803 = state_16691__$1;
(statearr_16791_18803[(2)] = null);

(statearr_16791_18803[(1)] = (15));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (16))){
var inst_16640 = (state_16691[(10)]);
var inst_16644 = cljs.core.chunk_first(inst_16640);
var inst_16645 = cljs.core.chunk_rest(inst_16640);
var inst_16647 = cljs.core.count(inst_16644);
var inst_16624 = inst_16645;
var inst_16625 = inst_16644;
var inst_16626 = inst_16647;
var inst_16627 = (0);
var state_16691__$1 = (function (){var statearr_16798 = state_16691;
(statearr_16798[(13)] = inst_16624);

(statearr_16798[(14)] = inst_16625);

(statearr_16798[(15)] = inst_16626);

(statearr_16798[(16)] = inst_16627);

return statearr_16798;
})();
var statearr_16801_18805 = state_16691__$1;
(statearr_16801_18805[(2)] = null);

(statearr_16801_18805[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (10))){
var inst_16625 = (state_16691[(14)]);
var inst_16627 = (state_16691[(16)]);
var inst_16624 = (state_16691[(13)]);
var inst_16626 = (state_16691[(15)]);
var inst_16633 = cljs.core._nth(inst_16625,inst_16627);
var inst_16635 = cljs.core.async.muxch_STAR_(inst_16633);
var inst_16636 = cljs.core.async.close_BANG_(inst_16635);
var inst_16637 = (inst_16627 + (1));
var tmp16786 = inst_16625;
var tmp16787 = inst_16624;
var tmp16788 = inst_16626;
var inst_16624__$1 = tmp16787;
var inst_16625__$1 = tmp16786;
var inst_16626__$1 = tmp16788;
var inst_16627__$1 = inst_16637;
var state_16691__$1 = (function (){var statearr_16806 = state_16691;
(statearr_16806[(17)] = inst_16636);

(statearr_16806[(13)] = inst_16624__$1);

(statearr_16806[(14)] = inst_16625__$1);

(statearr_16806[(15)] = inst_16626__$1);

(statearr_16806[(16)] = inst_16627__$1);

return statearr_16806;
})();
var statearr_16807_18815 = state_16691__$1;
(statearr_16807_18815[(2)] = null);

(statearr_16807_18815[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (18))){
var inst_16657 = (state_16691[(2)]);
var state_16691__$1 = state_16691;
var statearr_16808_18822 = state_16691__$1;
(statearr_16808_18822[(2)] = inst_16657);

(statearr_16808_18822[(1)] = (15));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16692 === (8))){
var inst_16627 = (state_16691[(16)]);
var inst_16626 = (state_16691[(15)]);
var inst_16630 = (inst_16627 < inst_16626);
var inst_16631 = inst_16630;
var state_16691__$1 = state_16691;
if(cljs.core.truth_(inst_16631)){
var statearr_16809_18833 = state_16691__$1;
(statearr_16809_18833[(1)] = (10));

} else {
var statearr_16811_18834 = state_16691__$1;
(statearr_16811_18834[(1)] = (11));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_16817 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_16817[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_16817[(1)] = (1));

return statearr_16817;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_16691){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_16691);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e16822){var ex__14017__auto__ = e16822;
var statearr_16823_18849 = state_16691;
(statearr_16823_18849[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_16691[(4)]))){
var statearr_16824_18852 = state_16691;
(statearr_16824_18852[(1)] = cljs.core.first((state_16691[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__18854 = state_16691;
state_16691 = G__18854;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_16691){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_16691);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_16829 = f__14504__auto__();
(statearr_16829[(6)] = c__14503__auto___18732);

return statearr_16829;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return p;
}));

(cljs.core.async.pub.cljs$lang$maxFixedArity = 3);

/**
 * Subscribes a channel to a topic of a pub.
 * 
 *   By default the channel will be closed when the source closes,
 *   but can be determined by the close? parameter.
 */
cljs.core.async.sub = (function cljs$core$async$sub(var_args){
var G__16835 = arguments.length;
switch (G__16835) {
case 3:
return cljs.core.async.sub.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
case 4:
return cljs.core.async.sub.cljs$core$IFn$_invoke$arity$4((arguments[(0)]),(arguments[(1)]),(arguments[(2)]),(arguments[(3)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.sub.cljs$core$IFn$_invoke$arity$3 = (function (p,topic,ch){
return cljs.core.async.sub.cljs$core$IFn$_invoke$arity$4(p,topic,ch,true);
}));

(cljs.core.async.sub.cljs$core$IFn$_invoke$arity$4 = (function (p,topic,ch,close_QMARK_){
return cljs.core.async.sub_STAR_(p,topic,ch,close_QMARK_);
}));

(cljs.core.async.sub.cljs$lang$maxFixedArity = 4);

/**
 * Unsubscribes a channel from a topic of a pub
 */
cljs.core.async.unsub = (function cljs$core$async$unsub(p,topic,ch){
return cljs.core.async.unsub_STAR_(p,topic,ch);
});
/**
 * Unsubscribes all channels from a pub, or a topic of a pub
 */
cljs.core.async.unsub_all = (function cljs$core$async$unsub_all(var_args){
var G__16844 = arguments.length;
switch (G__16844) {
case 1:
return cljs.core.async.unsub_all.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.unsub_all.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.unsub_all.cljs$core$IFn$_invoke$arity$1 = (function (p){
return cljs.core.async.unsub_all_STAR_(p);
}));

(cljs.core.async.unsub_all.cljs$core$IFn$_invoke$arity$2 = (function (p,topic){
return cljs.core.async.unsub_all_STAR_(p,topic);
}));

(cljs.core.async.unsub_all.cljs$lang$maxFixedArity = 2);

/**
 * Takes a function and a collection of source channels, and returns a
 *   channel which contains the values produced by applying f to the set
 *   of first items taken from each source channel, followed by applying
 *   f to the set of second items from each channel, until any one of the
 *   channels is closed, at which point the output channel will be
 *   closed. The returned channel will be unbuffered by default, or a
 *   buf-or-n can be supplied
 */
cljs.core.async.map = (function cljs$core$async$map(var_args){
var G__16846 = arguments.length;
switch (G__16846) {
case 2:
return cljs.core.async.map.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.map.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.map.cljs$core$IFn$_invoke$arity$2 = (function (f,chs){
return cljs.core.async.map.cljs$core$IFn$_invoke$arity$3(f,chs,null);
}));

(cljs.core.async.map.cljs$core$IFn$_invoke$arity$3 = (function (f,chs,buf_or_n){
var chs__$1 = cljs.core.vec(chs);
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var cnt = cljs.core.count(chs__$1);
var rets = cljs.core.object_array.cljs$core$IFn$_invoke$arity$1(cnt);
var dchan = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
var dctr = cljs.core.atom.cljs$core$IFn$_invoke$arity$1(null);
var done = cljs.core.mapv.cljs$core$IFn$_invoke$arity$2((function (i){
return (function (ret){
(rets[i] = ret);

if((cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(dctr,cljs.core.dec) === (0))){
return cljs.core.async.put_BANG_.cljs$core$IFn$_invoke$arity$2(dchan,rets.slice((0)));
} else {
return null;
}
});
}),cljs.core.range.cljs$core$IFn$_invoke$arity$1(cnt));
if((cnt === (0))){
cljs.core.async.close_BANG_(out);
} else {
var c__14503__auto___18927 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_16913){
var state_val_16914 = (state_16913[(1)]);
if((state_val_16914 === (7))){
var state_16913__$1 = state_16913;
var statearr_16916_18936 = state_16913__$1;
(statearr_16916_18936[(2)] = null);

(statearr_16916_18936[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (1))){
var state_16913__$1 = state_16913;
var statearr_16918_18941 = state_16913__$1;
(statearr_16918_18941[(2)] = null);

(statearr_16918_18941[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (4))){
var inst_16864 = (state_16913[(7)]);
var inst_16863 = (state_16913[(8)]);
var inst_16868 = (inst_16864 < inst_16863);
var state_16913__$1 = state_16913;
if(cljs.core.truth_(inst_16868)){
var statearr_16919_18953 = state_16913__$1;
(statearr_16919_18953[(1)] = (6));

} else {
var statearr_16920_18955 = state_16913__$1;
(statearr_16920_18955[(1)] = (7));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (15))){
var inst_16899 = (state_16913[(9)]);
var inst_16904 = cljs.core.apply.cljs$core$IFn$_invoke$arity$2(f,inst_16899);
var state_16913__$1 = state_16913;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_16913__$1,(17),out,inst_16904);
} else {
if((state_val_16914 === (13))){
var inst_16899 = (state_16913[(9)]);
var inst_16899__$1 = (state_16913[(2)]);
var inst_16900 = cljs.core.some(cljs.core.nil_QMARK_,inst_16899__$1);
var state_16913__$1 = (function (){var statearr_16923 = state_16913;
(statearr_16923[(9)] = inst_16899__$1);

return statearr_16923;
})();
if(cljs.core.truth_(inst_16900)){
var statearr_16928_18967 = state_16913__$1;
(statearr_16928_18967[(1)] = (14));

} else {
var statearr_16929_18968 = state_16913__$1;
(statearr_16929_18968[(1)] = (15));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (6))){
var state_16913__$1 = state_16913;
var statearr_16931_18969 = state_16913__$1;
(statearr_16931_18969[(2)] = null);

(statearr_16931_18969[(1)] = (9));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (17))){
var inst_16906 = (state_16913[(2)]);
var state_16913__$1 = (function (){var statearr_16941 = state_16913;
(statearr_16941[(10)] = inst_16906);

return statearr_16941;
})();
var statearr_16943_18978 = state_16913__$1;
(statearr_16943_18978[(2)] = null);

(statearr_16943_18978[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (3))){
var inst_16911 = (state_16913[(2)]);
var state_16913__$1 = state_16913;
return cljs.core.async.impl.ioc_helpers.return_chan(state_16913__$1,inst_16911);
} else {
if((state_val_16914 === (12))){
var _ = (function (){var statearr_16944 = state_16913;
(statearr_16944[(4)] = cljs.core.rest((state_16913[(4)])));

return statearr_16944;
})();
var state_16913__$1 = state_16913;
var ex16940 = (state_16913__$1[(2)]);
var statearr_16945_18979 = state_16913__$1;
(statearr_16945_18979[(5)] = ex16940);


if((ex16940 instanceof Object)){
var statearr_16946_18980 = state_16913__$1;
(statearr_16946_18980[(1)] = (11));

(statearr_16946_18980[(5)] = null);

} else {
throw ex16940;

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (2))){
var inst_16862 = cljs.core.reset_BANG_(dctr,cnt);
var inst_16863 = cnt;
var inst_16864 = (0);
var state_16913__$1 = (function (){var statearr_16947 = state_16913;
(statearr_16947[(11)] = inst_16862);

(statearr_16947[(8)] = inst_16863);

(statearr_16947[(7)] = inst_16864);

return statearr_16947;
})();
var statearr_16948_19004 = state_16913__$1;
(statearr_16948_19004[(2)] = null);

(statearr_16948_19004[(1)] = (4));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (11))){
var inst_16878 = (state_16913[(2)]);
var inst_16879 = cljs.core.swap_BANG_.cljs$core$IFn$_invoke$arity$2(dctr,cljs.core.dec);
var state_16913__$1 = (function (){var statearr_16950 = state_16913;
(statearr_16950[(12)] = inst_16878);

return statearr_16950;
})();
var statearr_16951_19006 = state_16913__$1;
(statearr_16951_19006[(2)] = inst_16879);

(statearr_16951_19006[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (9))){
var inst_16864 = (state_16913[(7)]);
var _ = (function (){var statearr_16952 = state_16913;
(statearr_16952[(4)] = cljs.core.cons((12),(state_16913[(4)])));

return statearr_16952;
})();
var inst_16885 = (chs__$1.cljs$core$IFn$_invoke$arity$1 ? chs__$1.cljs$core$IFn$_invoke$arity$1(inst_16864) : chs__$1.call(null,inst_16864));
var inst_16886 = (done.cljs$core$IFn$_invoke$arity$1 ? done.cljs$core$IFn$_invoke$arity$1(inst_16864) : done.call(null,inst_16864));
var inst_16887 = cljs.core.async.take_BANG_.cljs$core$IFn$_invoke$arity$2(inst_16885,inst_16886);
var ___$1 = (function (){var statearr_16953 = state_16913;
(statearr_16953[(4)] = cljs.core.rest((state_16913[(4)])));

return statearr_16953;
})();
var state_16913__$1 = state_16913;
var statearr_16954_19010 = state_16913__$1;
(statearr_16954_19010[(2)] = inst_16887);

(statearr_16954_19010[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (5))){
var inst_16897 = (state_16913[(2)]);
var state_16913__$1 = (function (){var statearr_16959 = state_16913;
(statearr_16959[(13)] = inst_16897);

return statearr_16959;
})();
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_16913__$1,(13),dchan);
} else {
if((state_val_16914 === (14))){
var inst_16902 = cljs.core.async.close_BANG_(out);
var state_16913__$1 = state_16913;
var statearr_16963_19012 = state_16913__$1;
(statearr_16963_19012[(2)] = inst_16902);

(statearr_16963_19012[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (16))){
var inst_16909 = (state_16913[(2)]);
var state_16913__$1 = state_16913;
var statearr_16965_19013 = state_16913__$1;
(statearr_16965_19013[(2)] = inst_16909);

(statearr_16965_19013[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (10))){
var inst_16864 = (state_16913[(7)]);
var inst_16890 = (state_16913[(2)]);
var inst_16891 = (inst_16864 + (1));
var inst_16864__$1 = inst_16891;
var state_16913__$1 = (function (){var statearr_16967 = state_16913;
(statearr_16967[(14)] = inst_16890);

(statearr_16967[(7)] = inst_16864__$1);

return statearr_16967;
})();
var statearr_16968_19015 = state_16913__$1;
(statearr_16968_19015[(2)] = null);

(statearr_16968_19015[(1)] = (4));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_16914 === (8))){
var inst_16895 = (state_16913[(2)]);
var state_16913__$1 = state_16913;
var statearr_16969_19016 = state_16913__$1;
(statearr_16969_19016[(2)] = inst_16895);

(statearr_16969_19016[(1)] = (5));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_16970 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_16970[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_16970[(1)] = (1));

return statearr_16970;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_16913){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_16913);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e16971){var ex__14017__auto__ = e16971;
var statearr_16973_19017 = state_16913;
(statearr_16973_19017[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_16913[(4)]))){
var statearr_16974_19018 = state_16913;
(statearr_16974_19018[(1)] = cljs.core.first((state_16913[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__19020 = state_16913;
state_16913 = G__19020;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_16913){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_16913);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_16976 = f__14504__auto__();
(statearr_16976[(6)] = c__14503__auto___18927);

return statearr_16976;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));

}

return out;
}));

(cljs.core.async.map.cljs$lang$maxFixedArity = 3);

/**
 * Takes a collection of source channels and returns a channel which
 *   contains all values taken from them. The returned channel will be
 *   unbuffered by default, or a buf-or-n can be supplied. The channel
 *   will close after all the source channels have closed.
 */
cljs.core.async.merge = (function cljs$core$async$merge(var_args){
var G__16985 = arguments.length;
switch (G__16985) {
case 1:
return cljs.core.async.merge.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.merge.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.merge.cljs$core$IFn$_invoke$arity$1 = (function (chs){
return cljs.core.async.merge.cljs$core$IFn$_invoke$arity$2(chs,null);
}));

(cljs.core.async.merge.cljs$core$IFn$_invoke$arity$2 = (function (chs,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__14503__auto___19028 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_17024){
var state_val_17025 = (state_17024[(1)]);
if((state_val_17025 === (7))){
var inst_16998 = (state_17024[(7)]);
var inst_16999 = (state_17024[(8)]);
var inst_16998__$1 = (state_17024[(2)]);
var inst_16999__$1 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_16998__$1,(0),null);
var inst_17000 = cljs.core.nth.cljs$core$IFn$_invoke$arity$3(inst_16998__$1,(1),null);
var inst_17001 = (inst_16999__$1 == null);
var state_17024__$1 = (function (){var statearr_17031 = state_17024;
(statearr_17031[(7)] = inst_16998__$1);

(statearr_17031[(8)] = inst_16999__$1);

(statearr_17031[(9)] = inst_17000);

return statearr_17031;
})();
if(cljs.core.truth_(inst_17001)){
var statearr_17036_19034 = state_17024__$1;
(statearr_17036_19034[(1)] = (8));

} else {
var statearr_17037_19035 = state_17024__$1;
(statearr_17037_19035[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17025 === (1))){
var inst_16988 = cljs.core.vec(chs);
var inst_16989 = inst_16988;
var state_17024__$1 = (function (){var statearr_17039 = state_17024;
(statearr_17039[(10)] = inst_16989);

return statearr_17039;
})();
var statearr_17040_19037 = state_17024__$1;
(statearr_17040_19037[(2)] = null);

(statearr_17040_19037[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17025 === (4))){
var inst_16989 = (state_17024[(10)]);
var state_17024__$1 = state_17024;
return cljs.core.async.ioc_alts_BANG_(state_17024__$1,(7),inst_16989);
} else {
if((state_val_17025 === (6))){
var inst_17015 = (state_17024[(2)]);
var state_17024__$1 = state_17024;
var statearr_17042_19042 = state_17024__$1;
(statearr_17042_19042[(2)] = inst_17015);

(statearr_17042_19042[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17025 === (3))){
var inst_17017 = (state_17024[(2)]);
var state_17024__$1 = state_17024;
return cljs.core.async.impl.ioc_helpers.return_chan(state_17024__$1,inst_17017);
} else {
if((state_val_17025 === (2))){
var inst_16989 = (state_17024[(10)]);
var inst_16991 = cljs.core.count(inst_16989);
var inst_16992 = (inst_16991 > (0));
var state_17024__$1 = state_17024;
if(cljs.core.truth_(inst_16992)){
var statearr_17049_19048 = state_17024__$1;
(statearr_17049_19048[(1)] = (4));

} else {
var statearr_17050_19049 = state_17024__$1;
(statearr_17050_19049[(1)] = (5));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17025 === (11))){
var inst_16989 = (state_17024[(10)]);
var inst_17008 = (state_17024[(2)]);
var tmp17046 = inst_16989;
var inst_16989__$1 = tmp17046;
var state_17024__$1 = (function (){var statearr_17051 = state_17024;
(statearr_17051[(11)] = inst_17008);

(statearr_17051[(10)] = inst_16989__$1);

return statearr_17051;
})();
var statearr_17053_19055 = state_17024__$1;
(statearr_17053_19055[(2)] = null);

(statearr_17053_19055[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17025 === (9))){
var inst_16999 = (state_17024[(8)]);
var state_17024__$1 = state_17024;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17024__$1,(11),out,inst_16999);
} else {
if((state_val_17025 === (5))){
var inst_17013 = cljs.core.async.close_BANG_(out);
var state_17024__$1 = state_17024;
var statearr_17060_19061 = state_17024__$1;
(statearr_17060_19061[(2)] = inst_17013);

(statearr_17060_19061[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17025 === (10))){
var inst_17011 = (state_17024[(2)]);
var state_17024__$1 = state_17024;
var statearr_17061_19063 = state_17024__$1;
(statearr_17061_19063[(2)] = inst_17011);

(statearr_17061_19063[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17025 === (8))){
var inst_16989 = (state_17024[(10)]);
var inst_16998 = (state_17024[(7)]);
var inst_16999 = (state_17024[(8)]);
var inst_17000 = (state_17024[(9)]);
var inst_17003 = (function (){var cs = inst_16989;
var vec__16994 = inst_16998;
var v = inst_16999;
var c = inst_17000;
return (function (p1__16983_SHARP_){
return cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(c,p1__16983_SHARP_);
});
})();
var inst_17004 = cljs.core.filterv(inst_17003,inst_16989);
var inst_16989__$1 = inst_17004;
var state_17024__$1 = (function (){var statearr_17062 = state_17024;
(statearr_17062[(10)] = inst_16989__$1);

return statearr_17062;
})();
var statearr_17063_19072 = state_17024__$1;
(statearr_17063_19072[(2)] = null);

(statearr_17063_19072[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_17064 = [null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_17064[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_17064[(1)] = (1));

return statearr_17064;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_17024){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_17024);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e17065){var ex__14017__auto__ = e17065;
var statearr_17066_19090 = state_17024;
(statearr_17066_19090[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_17024[(4)]))){
var statearr_17067_19092 = state_17024;
(statearr_17067_19092[(1)] = cljs.core.first((state_17024[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__19093 = state_17024;
state_17024 = G__19093;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_17024){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_17024);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_17068 = f__14504__auto__();
(statearr_17068[(6)] = c__14503__auto___19028);

return statearr_17068;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return out;
}));

(cljs.core.async.merge.cljs$lang$maxFixedArity = 2);

/**
 * Returns a channel containing the single (collection) result of the
 *   items taken from the channel conjoined to the supplied
 *   collection. ch must close before into produces a result.
 */
cljs.core.async.into = (function cljs$core$async$into(coll,ch){
return cljs.core.async.reduce(cljs.core.conj,coll,ch);
});
/**
 * Returns a channel that will return, at most, n items from ch. After n items
 * have been returned, or ch has been closed, the return chanel will close.
 * 
 *   The output channel is unbuffered by default, unless buf-or-n is given.
 */
cljs.core.async.take = (function cljs$core$async$take(var_args){
var G__17071 = arguments.length;
switch (G__17071) {
case 2:
return cljs.core.async.take.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.take.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.take.cljs$core$IFn$_invoke$arity$2 = (function (n,ch){
return cljs.core.async.take.cljs$core$IFn$_invoke$arity$3(n,ch,null);
}));

(cljs.core.async.take.cljs$core$IFn$_invoke$arity$3 = (function (n,ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__14503__auto___19106 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_17098){
var state_val_17099 = (state_17098[(1)]);
if((state_val_17099 === (7))){
var inst_17080 = (state_17098[(7)]);
var inst_17080__$1 = (state_17098[(2)]);
var inst_17081 = (inst_17080__$1 == null);
var inst_17082 = cljs.core.not(inst_17081);
var state_17098__$1 = (function (){var statearr_17100 = state_17098;
(statearr_17100[(7)] = inst_17080__$1);

return statearr_17100;
})();
if(inst_17082){
var statearr_17101_19113 = state_17098__$1;
(statearr_17101_19113[(1)] = (8));

} else {
var statearr_17102_19119 = state_17098__$1;
(statearr_17102_19119[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17099 === (1))){
var inst_17075 = (0);
var state_17098__$1 = (function (){var statearr_17103 = state_17098;
(statearr_17103[(8)] = inst_17075);

return statearr_17103;
})();
var statearr_17104_19121 = state_17098__$1;
(statearr_17104_19121[(2)] = null);

(statearr_17104_19121[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17099 === (4))){
var state_17098__$1 = state_17098;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_17098__$1,(7),ch);
} else {
if((state_val_17099 === (6))){
var inst_17093 = (state_17098[(2)]);
var state_17098__$1 = state_17098;
var statearr_17105_19126 = state_17098__$1;
(statearr_17105_19126[(2)] = inst_17093);

(statearr_17105_19126[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17099 === (3))){
var inst_17095 = (state_17098[(2)]);
var inst_17096 = cljs.core.async.close_BANG_(out);
var state_17098__$1 = (function (){var statearr_17108 = state_17098;
(statearr_17108[(9)] = inst_17095);

return statearr_17108;
})();
return cljs.core.async.impl.ioc_helpers.return_chan(state_17098__$1,inst_17096);
} else {
if((state_val_17099 === (2))){
var inst_17075 = (state_17098[(8)]);
var inst_17077 = (inst_17075 < n);
var state_17098__$1 = state_17098;
if(cljs.core.truth_(inst_17077)){
var statearr_17111_19140 = state_17098__$1;
(statearr_17111_19140[(1)] = (4));

} else {
var statearr_17112_19141 = state_17098__$1;
(statearr_17112_19141[(1)] = (5));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17099 === (11))){
var inst_17075 = (state_17098[(8)]);
var inst_17085 = (state_17098[(2)]);
var inst_17086 = (inst_17075 + (1));
var inst_17075__$1 = inst_17086;
var state_17098__$1 = (function (){var statearr_17122 = state_17098;
(statearr_17122[(10)] = inst_17085);

(statearr_17122[(8)] = inst_17075__$1);

return statearr_17122;
})();
var statearr_17127_19145 = state_17098__$1;
(statearr_17127_19145[(2)] = null);

(statearr_17127_19145[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17099 === (9))){
var state_17098__$1 = state_17098;
var statearr_17128_19147 = state_17098__$1;
(statearr_17128_19147[(2)] = null);

(statearr_17128_19147[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17099 === (5))){
var state_17098__$1 = state_17098;
var statearr_17129_19155 = state_17098__$1;
(statearr_17129_19155[(2)] = null);

(statearr_17129_19155[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17099 === (10))){
var inst_17090 = (state_17098[(2)]);
var state_17098__$1 = state_17098;
var statearr_17132_19163 = state_17098__$1;
(statearr_17132_19163[(2)] = inst_17090);

(statearr_17132_19163[(1)] = (6));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17099 === (8))){
var inst_17080 = (state_17098[(7)]);
var state_17098__$1 = state_17098;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17098__$1,(11),out,inst_17080);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_17140 = [null,null,null,null,null,null,null,null,null,null,null];
(statearr_17140[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_17140[(1)] = (1));

return statearr_17140;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_17098){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_17098);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e17142){var ex__14017__auto__ = e17142;
var statearr_17143_19179 = state_17098;
(statearr_17143_19179[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_17098[(4)]))){
var statearr_17145_19180 = state_17098;
(statearr_17145_19180[(1)] = cljs.core.first((state_17098[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__19181 = state_17098;
state_17098 = G__19181;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_17098){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_17098);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_17147 = f__14504__auto__();
(statearr_17147[(6)] = c__14503__auto___19106);

return statearr_17147;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return out;
}));

(cljs.core.async.take.cljs$lang$maxFixedArity = 3);


/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Handler}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async17161 = (function (f,ch,meta17155,_,fn1,meta17162){
this.f = f;
this.ch = ch;
this.meta17155 = meta17155;
this._ = _;
this.fn1 = fn1;
this.meta17162 = meta17162;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async17161.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_17163,meta17162__$1){
var self__ = this;
var _17163__$1 = this;
return (new cljs.core.async.t_cljs$core$async17161(self__.f,self__.ch,self__.meta17155,self__._,self__.fn1,meta17162__$1));
}));

(cljs.core.async.t_cljs$core$async17161.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_17163){
var self__ = this;
var _17163__$1 = this;
return self__.meta17162;
}));

(cljs.core.async.t_cljs$core$async17161.prototype.cljs$core$async$impl$protocols$Handler$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17161.prototype.cljs$core$async$impl$protocols$Handler$active_QMARK_$arity$1 = (function (___$1){
var self__ = this;
var ___$2 = this;
return cljs.core.async.impl.protocols.active_QMARK_(self__.fn1);
}));

(cljs.core.async.t_cljs$core$async17161.prototype.cljs$core$async$impl$protocols$Handler$blockable_QMARK_$arity$1 = (function (___$1){
var self__ = this;
var ___$2 = this;
return true;
}));

(cljs.core.async.t_cljs$core$async17161.prototype.cljs$core$async$impl$protocols$Handler$commit$arity$1 = (function (___$1){
var self__ = this;
var ___$2 = this;
var f1 = cljs.core.async.impl.protocols.commit(self__.fn1);
return (function (p1__17149_SHARP_){
var G__17168 = (((p1__17149_SHARP_ == null))?null:(self__.f.cljs$core$IFn$_invoke$arity$1 ? self__.f.cljs$core$IFn$_invoke$arity$1(p1__17149_SHARP_) : self__.f.call(null,p1__17149_SHARP_)));
return (f1.cljs$core$IFn$_invoke$arity$1 ? f1.cljs$core$IFn$_invoke$arity$1(G__17168) : f1.call(null,G__17168));
});
}));

(cljs.core.async.t_cljs$core$async17161.getBasis = (function (){
return new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"meta17155","meta17155",-1450937728,null),cljs.core.with_meta(new cljs.core.Symbol(null,"_","_",-1201019570,null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"tag","tag",-1290361223),new cljs.core.Symbol("cljs.core.async","t_cljs$core$async17154","cljs.core.async/t_cljs$core$async17154",-1460283774,null)], null)),new cljs.core.Symbol(null,"fn1","fn1",895834444,null),new cljs.core.Symbol(null,"meta17162","meta17162",-431522870,null)], null);
}));

(cljs.core.async.t_cljs$core$async17161.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async17161.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async17161");

(cljs.core.async.t_cljs$core$async17161.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async17161");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async17161.
 */
cljs.core.async.__GT_t_cljs$core$async17161 = (function cljs$core$async$__GT_t_cljs$core$async17161(f,ch,meta17155,_,fn1,meta17162){
return (new cljs.core.async.t_cljs$core$async17161(f,ch,meta17155,_,fn1,meta17162));
});



/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Channel}
 * @implements {cljs.core.async.impl.protocols.WritePort}
 * @implements {cljs.core.async.impl.protocols.ReadPort}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async17154 = (function (f,ch,meta17155){
this.f = f;
this.ch = ch;
this.meta17155 = meta17155;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_17156,meta17155__$1){
var self__ = this;
var _17156__$1 = this;
return (new cljs.core.async.t_cljs$core$async17154(self__.f,self__.ch,meta17155__$1));
}));

(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_17156){
var self__ = this;
var _17156__$1 = this;
return self__.meta17155;
}));

(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$async$impl$protocols$Channel$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$async$impl$protocols$Channel$close_BANG_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.close_BANG_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$async$impl$protocols$Channel$closed_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.closed_QMARK_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$async$impl$protocols$ReadPort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$async$impl$protocols$ReadPort$take_BANG_$arity$2 = (function (_,fn1){
var self__ = this;
var ___$1 = this;
var ret = cljs.core.async.impl.protocols.take_BANG_(self__.ch,(new cljs.core.async.t_cljs$core$async17161(self__.f,self__.ch,self__.meta17155,___$1,fn1,cljs.core.PersistentArrayMap.EMPTY)));
if(cljs.core.truth_((function (){var and__5140__auto__ = ret;
if(cljs.core.truth_(and__5140__auto__)){
return (!((cljs.core.deref(ret) == null)));
} else {
return and__5140__auto__;
}
})())){
return cljs.core.async.impl.channels.box((function (){var G__17190 = cljs.core.deref(ret);
return (self__.f.cljs$core$IFn$_invoke$arity$1 ? self__.f.cljs$core$IFn$_invoke$arity$1(G__17190) : self__.f.call(null,G__17190));
})());
} else {
return ret;
}
}));

(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$async$impl$protocols$WritePort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17154.prototype.cljs$core$async$impl$protocols$WritePort$put_BANG_$arity$3 = (function (_,val,fn1){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.put_BANG_(self__.ch,val,fn1);
}));

(cljs.core.async.t_cljs$core$async17154.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"meta17155","meta17155",-1450937728,null)], null);
}));

(cljs.core.async.t_cljs$core$async17154.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async17154.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async17154");

(cljs.core.async.t_cljs$core$async17154.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async17154");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async17154.
 */
cljs.core.async.__GT_t_cljs$core$async17154 = (function cljs$core$async$__GT_t_cljs$core$async17154(f,ch,meta17155){
return (new cljs.core.async.t_cljs$core$async17154(f,ch,meta17155));
});


/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.map_LT_ = (function cljs$core$async$map_LT_(f,ch){
return (new cljs.core.async.t_cljs$core$async17154(f,ch,cljs.core.PersistentArrayMap.EMPTY));
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Channel}
 * @implements {cljs.core.async.impl.protocols.WritePort}
 * @implements {cljs.core.async.impl.protocols.ReadPort}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async17215 = (function (f,ch,meta17216){
this.f = f;
this.ch = ch;
this.meta17216 = meta17216;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async17215.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_17217,meta17216__$1){
var self__ = this;
var _17217__$1 = this;
return (new cljs.core.async.t_cljs$core$async17215(self__.f,self__.ch,meta17216__$1));
}));

(cljs.core.async.t_cljs$core$async17215.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_17217){
var self__ = this;
var _17217__$1 = this;
return self__.meta17216;
}));

(cljs.core.async.t_cljs$core$async17215.prototype.cljs$core$async$impl$protocols$Channel$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17215.prototype.cljs$core$async$impl$protocols$Channel$close_BANG_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.close_BANG_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async17215.prototype.cljs$core$async$impl$protocols$ReadPort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17215.prototype.cljs$core$async$impl$protocols$ReadPort$take_BANG_$arity$2 = (function (_,fn1){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.take_BANG_(self__.ch,fn1);
}));

(cljs.core.async.t_cljs$core$async17215.prototype.cljs$core$async$impl$protocols$WritePort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17215.prototype.cljs$core$async$impl$protocols$WritePort$put_BANG_$arity$3 = (function (_,val,fn1){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.put_BANG_(self__.ch,(self__.f.cljs$core$IFn$_invoke$arity$1 ? self__.f.cljs$core$IFn$_invoke$arity$1(val) : self__.f.call(null,val)),fn1);
}));

(cljs.core.async.t_cljs$core$async17215.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"meta17216","meta17216",1217495831,null)], null);
}));

(cljs.core.async.t_cljs$core$async17215.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async17215.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async17215");

(cljs.core.async.t_cljs$core$async17215.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async17215");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async17215.
 */
cljs.core.async.__GT_t_cljs$core$async17215 = (function cljs$core$async$__GT_t_cljs$core$async17215(f,ch,meta17216){
return (new cljs.core.async.t_cljs$core$async17215(f,ch,meta17216));
});


/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.map_GT_ = (function cljs$core$async$map_GT_(f,ch){
return (new cljs.core.async.t_cljs$core$async17215(f,ch,cljs.core.PersistentArrayMap.EMPTY));
});

/**
* @constructor
 * @implements {cljs.core.async.impl.protocols.Channel}
 * @implements {cljs.core.async.impl.protocols.WritePort}
 * @implements {cljs.core.async.impl.protocols.ReadPort}
 * @implements {cljs.core.IMeta}
 * @implements {cljs.core.IWithMeta}
*/
cljs.core.async.t_cljs$core$async17238 = (function (p,ch,meta17239){
this.p = p;
this.ch = ch;
this.meta17239 = meta17239;
this.cljs$lang$protocol_mask$partition0$ = 393216;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$IWithMeta$_with_meta$arity$2 = (function (_17240,meta17239__$1){
var self__ = this;
var _17240__$1 = this;
return (new cljs.core.async.t_cljs$core$async17238(self__.p,self__.ch,meta17239__$1));
}));

(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$IMeta$_meta$arity$1 = (function (_17240){
var self__ = this;
var _17240__$1 = this;
return self__.meta17239;
}));

(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$async$impl$protocols$Channel$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$async$impl$protocols$Channel$close_BANG_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.close_BANG_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$async$impl$protocols$Channel$closed_QMARK_$arity$1 = (function (_){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.closed_QMARK_(self__.ch);
}));

(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$async$impl$protocols$ReadPort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$async$impl$protocols$ReadPort$take_BANG_$arity$2 = (function (_,fn1){
var self__ = this;
var ___$1 = this;
return cljs.core.async.impl.protocols.take_BANG_(self__.ch,fn1);
}));

(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$async$impl$protocols$WritePort$ = cljs.core.PROTOCOL_SENTINEL);

(cljs.core.async.t_cljs$core$async17238.prototype.cljs$core$async$impl$protocols$WritePort$put_BANG_$arity$3 = (function (_,val,fn1){
var self__ = this;
var ___$1 = this;
if(cljs.core.truth_((self__.p.cljs$core$IFn$_invoke$arity$1 ? self__.p.cljs$core$IFn$_invoke$arity$1(val) : self__.p.call(null,val)))){
return cljs.core.async.impl.protocols.put_BANG_(self__.ch,val,fn1);
} else {
return cljs.core.async.impl.channels.box(cljs.core.not(cljs.core.async.impl.protocols.closed_QMARK_(self__.ch)));
}
}));

(cljs.core.async.t_cljs$core$async17238.getBasis = (function (){
return new cljs.core.PersistentVector(null, 3, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"p","p",1791580836,null),new cljs.core.Symbol(null,"ch","ch",1085813622,null),new cljs.core.Symbol(null,"meta17239","meta17239",-2030902171,null)], null);
}));

(cljs.core.async.t_cljs$core$async17238.cljs$lang$type = true);

(cljs.core.async.t_cljs$core$async17238.cljs$lang$ctorStr = "cljs.core.async/t_cljs$core$async17238");

(cljs.core.async.t_cljs$core$async17238.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.core.async/t_cljs$core$async17238");
}));

/**
 * Positional factory function for cljs.core.async/t_cljs$core$async17238.
 */
cljs.core.async.__GT_t_cljs$core$async17238 = (function cljs$core$async$__GT_t_cljs$core$async17238(p,ch,meta17239){
return (new cljs.core.async.t_cljs$core$async17238(p,ch,meta17239));
});


/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.filter_GT_ = (function cljs$core$async$filter_GT_(p,ch){
return (new cljs.core.async.t_cljs$core$async17238(p,ch,cljs.core.PersistentArrayMap.EMPTY));
});
/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.remove_GT_ = (function cljs$core$async$remove_GT_(p,ch){
return cljs.core.async.filter_GT_(cljs.core.complement(p),ch);
});
/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.filter_LT_ = (function cljs$core$async$filter_LT_(var_args){
var G__17250 = arguments.length;
switch (G__17250) {
case 2:
return cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$2 = (function (p,ch){
return cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$3(p,ch,null);
}));

(cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$3 = (function (p,ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__14503__auto___19249 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_17284){
var state_val_17285 = (state_17284[(1)]);
if((state_val_17285 === (7))){
var inst_17279 = (state_17284[(2)]);
var state_17284__$1 = state_17284;
var statearr_17289_19251 = state_17284__$1;
(statearr_17289_19251[(2)] = inst_17279);

(statearr_17289_19251[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17285 === (1))){
var state_17284__$1 = state_17284;
var statearr_17292_19252 = state_17284__$1;
(statearr_17292_19252[(2)] = null);

(statearr_17292_19252[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17285 === (4))){
var inst_17264 = (state_17284[(7)]);
var inst_17264__$1 = (state_17284[(2)]);
var inst_17265 = (inst_17264__$1 == null);
var state_17284__$1 = (function (){var statearr_17293 = state_17284;
(statearr_17293[(7)] = inst_17264__$1);

return statearr_17293;
})();
if(cljs.core.truth_(inst_17265)){
var statearr_17294_19254 = state_17284__$1;
(statearr_17294_19254[(1)] = (5));

} else {
var statearr_17295_19255 = state_17284__$1;
(statearr_17295_19255[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17285 === (6))){
var inst_17264 = (state_17284[(7)]);
var inst_17269 = (p.cljs$core$IFn$_invoke$arity$1 ? p.cljs$core$IFn$_invoke$arity$1(inst_17264) : p.call(null,inst_17264));
var state_17284__$1 = state_17284;
if(cljs.core.truth_(inst_17269)){
var statearr_17299_19256 = state_17284__$1;
(statearr_17299_19256[(1)] = (8));

} else {
var statearr_17300_19257 = state_17284__$1;
(statearr_17300_19257[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17285 === (3))){
var inst_17281 = (state_17284[(2)]);
var state_17284__$1 = state_17284;
return cljs.core.async.impl.ioc_helpers.return_chan(state_17284__$1,inst_17281);
} else {
if((state_val_17285 === (2))){
var state_17284__$1 = state_17284;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_17284__$1,(4),ch);
} else {
if((state_val_17285 === (11))){
var inst_17272 = (state_17284[(2)]);
var state_17284__$1 = state_17284;
var statearr_17308_19260 = state_17284__$1;
(statearr_17308_19260[(2)] = inst_17272);

(statearr_17308_19260[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17285 === (9))){
var state_17284__$1 = state_17284;
var statearr_17316_19261 = state_17284__$1;
(statearr_17316_19261[(2)] = null);

(statearr_17316_19261[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17285 === (5))){
var inst_17267 = cljs.core.async.close_BANG_(out);
var state_17284__$1 = state_17284;
var statearr_17324_19262 = state_17284__$1;
(statearr_17324_19262[(2)] = inst_17267);

(statearr_17324_19262[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17285 === (10))){
var inst_17275 = (state_17284[(2)]);
var state_17284__$1 = (function (){var statearr_17325 = state_17284;
(statearr_17325[(8)] = inst_17275);

return statearr_17325;
})();
var statearr_17326_19263 = state_17284__$1;
(statearr_17326_19263[(2)] = null);

(statearr_17326_19263[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17285 === (8))){
var inst_17264 = (state_17284[(7)]);
var state_17284__$1 = state_17284;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17284__$1,(11),out,inst_17264);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_17329 = [null,null,null,null,null,null,null,null,null];
(statearr_17329[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_17329[(1)] = (1));

return statearr_17329;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_17284){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_17284);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e17330){var ex__14017__auto__ = e17330;
var statearr_17331_19267 = state_17284;
(statearr_17331_19267[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_17284[(4)]))){
var statearr_17332_19268 = state_17284;
(statearr_17332_19268[(1)] = cljs.core.first((state_17284[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__19271 = state_17284;
state_17284 = G__19271;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_17284){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_17284);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_17333 = f__14504__auto__();
(statearr_17333[(6)] = c__14503__auto___19249);

return statearr_17333;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return out;
}));

(cljs.core.async.filter_LT_.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.remove_LT_ = (function cljs$core$async$remove_LT_(var_args){
var G__17337 = arguments.length;
switch (G__17337) {
case 2:
return cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$2 = (function (p,ch){
return cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$3(p,ch,null);
}));

(cljs.core.async.remove_LT_.cljs$core$IFn$_invoke$arity$3 = (function (p,ch,buf_or_n){
return cljs.core.async.filter_LT_.cljs$core$IFn$_invoke$arity$3(cljs.core.complement(p),ch,buf_or_n);
}));

(cljs.core.async.remove_LT_.cljs$lang$maxFixedArity = 3);

cljs.core.async.mapcat_STAR_ = (function cljs$core$async$mapcat_STAR_(f,in$,out){
var c__14503__auto__ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_17404){
var state_val_17405 = (state_17404[(1)]);
if((state_val_17405 === (7))){
var inst_17399 = (state_17404[(2)]);
var state_17404__$1 = state_17404;
var statearr_17410_19273 = state_17404__$1;
(statearr_17410_19273[(2)] = inst_17399);

(statearr_17410_19273[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (20))){
var inst_17369 = (state_17404[(7)]);
var inst_17380 = (state_17404[(2)]);
var inst_17381 = cljs.core.next(inst_17369);
var inst_17354 = inst_17381;
var inst_17355 = null;
var inst_17356 = (0);
var inst_17357 = (0);
var state_17404__$1 = (function (){var statearr_17412 = state_17404;
(statearr_17412[(8)] = inst_17380);

(statearr_17412[(9)] = inst_17354);

(statearr_17412[(10)] = inst_17355);

(statearr_17412[(11)] = inst_17356);

(statearr_17412[(12)] = inst_17357);

return statearr_17412;
})();
var statearr_17413_19280 = state_17404__$1;
(statearr_17413_19280[(2)] = null);

(statearr_17413_19280[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (1))){
var state_17404__$1 = state_17404;
var statearr_17415_19281 = state_17404__$1;
(statearr_17415_19281[(2)] = null);

(statearr_17415_19281[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (4))){
var inst_17343 = (state_17404[(13)]);
var inst_17343__$1 = (state_17404[(2)]);
var inst_17344 = (inst_17343__$1 == null);
var state_17404__$1 = (function (){var statearr_17420 = state_17404;
(statearr_17420[(13)] = inst_17343__$1);

return statearr_17420;
})();
if(cljs.core.truth_(inst_17344)){
var statearr_17421_19290 = state_17404__$1;
(statearr_17421_19290[(1)] = (5));

} else {
var statearr_17422_19291 = state_17404__$1;
(statearr_17422_19291[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (15))){
var state_17404__$1 = state_17404;
var statearr_17427_19292 = state_17404__$1;
(statearr_17427_19292[(2)] = null);

(statearr_17427_19292[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (21))){
var state_17404__$1 = state_17404;
var statearr_17429_19297 = state_17404__$1;
(statearr_17429_19297[(2)] = null);

(statearr_17429_19297[(1)] = (23));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (13))){
var inst_17357 = (state_17404[(12)]);
var inst_17354 = (state_17404[(9)]);
var inst_17355 = (state_17404[(10)]);
var inst_17356 = (state_17404[(11)]);
var inst_17365 = (state_17404[(2)]);
var inst_17366 = (inst_17357 + (1));
var tmp17423 = inst_17355;
var tmp17424 = inst_17354;
var tmp17425 = inst_17356;
var inst_17354__$1 = tmp17424;
var inst_17355__$1 = tmp17423;
var inst_17356__$1 = tmp17425;
var inst_17357__$1 = inst_17366;
var state_17404__$1 = (function (){var statearr_17430 = state_17404;
(statearr_17430[(14)] = inst_17365);

(statearr_17430[(9)] = inst_17354__$1);

(statearr_17430[(10)] = inst_17355__$1);

(statearr_17430[(11)] = inst_17356__$1);

(statearr_17430[(12)] = inst_17357__$1);

return statearr_17430;
})();
var statearr_17431_19299 = state_17404__$1;
(statearr_17431_19299[(2)] = null);

(statearr_17431_19299[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (22))){
var state_17404__$1 = state_17404;
var statearr_17432_19300 = state_17404__$1;
(statearr_17432_19300[(2)] = null);

(statearr_17432_19300[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (6))){
var inst_17343 = (state_17404[(13)]);
var inst_17352 = (f.cljs$core$IFn$_invoke$arity$1 ? f.cljs$core$IFn$_invoke$arity$1(inst_17343) : f.call(null,inst_17343));
var inst_17353 = cljs.core.seq(inst_17352);
var inst_17354 = inst_17353;
var inst_17355 = null;
var inst_17356 = (0);
var inst_17357 = (0);
var state_17404__$1 = (function (){var statearr_17433 = state_17404;
(statearr_17433[(9)] = inst_17354);

(statearr_17433[(10)] = inst_17355);

(statearr_17433[(11)] = inst_17356);

(statearr_17433[(12)] = inst_17357);

return statearr_17433;
})();
var statearr_17436_19303 = state_17404__$1;
(statearr_17436_19303[(2)] = null);

(statearr_17436_19303[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (17))){
var inst_17369 = (state_17404[(7)]);
var inst_17373 = cljs.core.chunk_first(inst_17369);
var inst_17374 = cljs.core.chunk_rest(inst_17369);
var inst_17375 = cljs.core.count(inst_17373);
var inst_17354 = inst_17374;
var inst_17355 = inst_17373;
var inst_17356 = inst_17375;
var inst_17357 = (0);
var state_17404__$1 = (function (){var statearr_17441 = state_17404;
(statearr_17441[(9)] = inst_17354);

(statearr_17441[(10)] = inst_17355);

(statearr_17441[(11)] = inst_17356);

(statearr_17441[(12)] = inst_17357);

return statearr_17441;
})();
var statearr_17442_19305 = state_17404__$1;
(statearr_17442_19305[(2)] = null);

(statearr_17442_19305[(1)] = (8));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (3))){
var inst_17401 = (state_17404[(2)]);
var state_17404__$1 = state_17404;
return cljs.core.async.impl.ioc_helpers.return_chan(state_17404__$1,inst_17401);
} else {
if((state_val_17405 === (12))){
var inst_17389 = (state_17404[(2)]);
var state_17404__$1 = state_17404;
var statearr_17443_19306 = state_17404__$1;
(statearr_17443_19306[(2)] = inst_17389);

(statearr_17443_19306[(1)] = (9));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (2))){
var state_17404__$1 = state_17404;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_17404__$1,(4),in$);
} else {
if((state_val_17405 === (23))){
var inst_17397 = (state_17404[(2)]);
var state_17404__$1 = state_17404;
var statearr_17447_19310 = state_17404__$1;
(statearr_17447_19310[(2)] = inst_17397);

(statearr_17447_19310[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (19))){
var inst_17384 = (state_17404[(2)]);
var state_17404__$1 = state_17404;
var statearr_17448_19311 = state_17404__$1;
(statearr_17448_19311[(2)] = inst_17384);

(statearr_17448_19311[(1)] = (16));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (11))){
var inst_17354 = (state_17404[(9)]);
var inst_17369 = (state_17404[(7)]);
var inst_17369__$1 = cljs.core.seq(inst_17354);
var state_17404__$1 = (function (){var statearr_17453 = state_17404;
(statearr_17453[(7)] = inst_17369__$1);

return statearr_17453;
})();
if(inst_17369__$1){
var statearr_17454_19316 = state_17404__$1;
(statearr_17454_19316[(1)] = (14));

} else {
var statearr_17457_19317 = state_17404__$1;
(statearr_17457_19317[(1)] = (15));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (9))){
var inst_17391 = (state_17404[(2)]);
var inst_17392 = cljs.core.async.impl.protocols.closed_QMARK_(out);
var state_17404__$1 = (function (){var statearr_17461 = state_17404;
(statearr_17461[(15)] = inst_17391);

return statearr_17461;
})();
if(cljs.core.truth_(inst_17392)){
var statearr_17462_19323 = state_17404__$1;
(statearr_17462_19323[(1)] = (21));

} else {
var statearr_17463_19324 = state_17404__$1;
(statearr_17463_19324[(1)] = (22));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (5))){
var inst_17346 = cljs.core.async.close_BANG_(out);
var state_17404__$1 = state_17404;
var statearr_17464_19325 = state_17404__$1;
(statearr_17464_19325[(2)] = inst_17346);

(statearr_17464_19325[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (14))){
var inst_17369 = (state_17404[(7)]);
var inst_17371 = cljs.core.chunked_seq_QMARK_(inst_17369);
var state_17404__$1 = state_17404;
if(inst_17371){
var statearr_17465_19328 = state_17404__$1;
(statearr_17465_19328[(1)] = (17));

} else {
var statearr_17466_19333 = state_17404__$1;
(statearr_17466_19333[(1)] = (18));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (16))){
var inst_17387 = (state_17404[(2)]);
var state_17404__$1 = state_17404;
var statearr_17467_19334 = state_17404__$1;
(statearr_17467_19334[(2)] = inst_17387);

(statearr_17467_19334[(1)] = (12));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17405 === (10))){
var inst_17355 = (state_17404[(10)]);
var inst_17357 = (state_17404[(12)]);
var inst_17362 = cljs.core._nth(inst_17355,inst_17357);
var state_17404__$1 = state_17404;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17404__$1,(13),out,inst_17362);
} else {
if((state_val_17405 === (18))){
var inst_17369 = (state_17404[(7)]);
var inst_17378 = cljs.core.first(inst_17369);
var state_17404__$1 = state_17404;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17404__$1,(20),out,inst_17378);
} else {
if((state_val_17405 === (8))){
var inst_17357 = (state_17404[(12)]);
var inst_17356 = (state_17404[(11)]);
var inst_17359 = (inst_17357 < inst_17356);
var inst_17360 = inst_17359;
var state_17404__$1 = state_17404;
if(cljs.core.truth_(inst_17360)){
var statearr_17471_19335 = state_17404__$1;
(statearr_17471_19335[(1)] = (10));

} else {
var statearr_17472_19336 = state_17404__$1;
(statearr_17472_19336[(1)] = (11));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$mapcat_STAR__$_state_machine__14014__auto__ = null;
var cljs$core$async$mapcat_STAR__$_state_machine__14014__auto____0 = (function (){
var statearr_17483 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_17483[(0)] = cljs$core$async$mapcat_STAR__$_state_machine__14014__auto__);

(statearr_17483[(1)] = (1));

return statearr_17483;
});
var cljs$core$async$mapcat_STAR__$_state_machine__14014__auto____1 = (function (state_17404){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_17404);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e17490){var ex__14017__auto__ = e17490;
var statearr_17494_19338 = state_17404;
(statearr_17494_19338[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_17404[(4)]))){
var statearr_17495_19339 = state_17404;
(statearr_17495_19339[(1)] = cljs.core.first((state_17404[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__19343 = state_17404;
state_17404 = G__19343;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$mapcat_STAR__$_state_machine__14014__auto__ = function(state_17404){
switch(arguments.length){
case 0:
return cljs$core$async$mapcat_STAR__$_state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$mapcat_STAR__$_state_machine__14014__auto____1.call(this,state_17404);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$mapcat_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$mapcat_STAR__$_state_machine__14014__auto____0;
cljs$core$async$mapcat_STAR__$_state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$mapcat_STAR__$_state_machine__14014__auto____1;
return cljs$core$async$mapcat_STAR__$_state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_17496 = f__14504__auto__();
(statearr_17496[(6)] = c__14503__auto__);

return statearr_17496;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));

return c__14503__auto__;
});
/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.mapcat_LT_ = (function cljs$core$async$mapcat_LT_(var_args){
var G__17516 = arguments.length;
switch (G__17516) {
case 2:
return cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$2 = (function (f,in$){
return cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$3(f,in$,null);
}));

(cljs.core.async.mapcat_LT_.cljs$core$IFn$_invoke$arity$3 = (function (f,in$,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
cljs.core.async.mapcat_STAR_(f,in$,out);

return out;
}));

(cljs.core.async.mapcat_LT_.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.mapcat_GT_ = (function cljs$core$async$mapcat_GT_(var_args){
var G__17537 = arguments.length;
switch (G__17537) {
case 2:
return cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$2 = (function (f,out){
return cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$3(f,out,null);
}));

(cljs.core.async.mapcat_GT_.cljs$core$IFn$_invoke$arity$3 = (function (f,out,buf_or_n){
var in$ = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
cljs.core.async.mapcat_STAR_(f,in$,out);

return in$;
}));

(cljs.core.async.mapcat_GT_.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.unique = (function cljs$core$async$unique(var_args){
var G__17554 = arguments.length;
switch (G__17554) {
case 1:
return cljs.core.async.unique.cljs$core$IFn$_invoke$arity$1((arguments[(0)]));

break;
case 2:
return cljs.core.async.unique.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.unique.cljs$core$IFn$_invoke$arity$1 = (function (ch){
return cljs.core.async.unique.cljs$core$IFn$_invoke$arity$2(ch,null);
}));

(cljs.core.async.unique.cljs$core$IFn$_invoke$arity$2 = (function (ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__14503__auto___19363 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_17579){
var state_val_17580 = (state_17579[(1)]);
if((state_val_17580 === (7))){
var inst_17574 = (state_17579[(2)]);
var state_17579__$1 = state_17579;
var statearr_17584_19364 = state_17579__$1;
(statearr_17584_19364[(2)] = inst_17574);

(statearr_17584_19364[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17580 === (1))){
var inst_17556 = null;
var state_17579__$1 = (function (){var statearr_17585 = state_17579;
(statearr_17585[(7)] = inst_17556);

return statearr_17585;
})();
var statearr_17586_19370 = state_17579__$1;
(statearr_17586_19370[(2)] = null);

(statearr_17586_19370[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17580 === (4))){
var inst_17559 = (state_17579[(8)]);
var inst_17559__$1 = (state_17579[(2)]);
var inst_17560 = (inst_17559__$1 == null);
var inst_17561 = cljs.core.not(inst_17560);
var state_17579__$1 = (function (){var statearr_17587 = state_17579;
(statearr_17587[(8)] = inst_17559__$1);

return statearr_17587;
})();
if(inst_17561){
var statearr_17588_19371 = state_17579__$1;
(statearr_17588_19371[(1)] = (5));

} else {
var statearr_17589_19372 = state_17579__$1;
(statearr_17589_19372[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17580 === (6))){
var state_17579__$1 = state_17579;
var statearr_17590_19375 = state_17579__$1;
(statearr_17590_19375[(2)] = null);

(statearr_17590_19375[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17580 === (3))){
var inst_17576 = (state_17579[(2)]);
var inst_17577 = cljs.core.async.close_BANG_(out);
var state_17579__$1 = (function (){var statearr_17591 = state_17579;
(statearr_17591[(9)] = inst_17576);

return statearr_17591;
})();
return cljs.core.async.impl.ioc_helpers.return_chan(state_17579__$1,inst_17577);
} else {
if((state_val_17580 === (2))){
var state_17579__$1 = state_17579;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_17579__$1,(4),ch);
} else {
if((state_val_17580 === (11))){
var inst_17559 = (state_17579[(8)]);
var inst_17568 = (state_17579[(2)]);
var inst_17556 = inst_17559;
var state_17579__$1 = (function (){var statearr_17592 = state_17579;
(statearr_17592[(10)] = inst_17568);

(statearr_17592[(7)] = inst_17556);

return statearr_17592;
})();
var statearr_17593_19388 = state_17579__$1;
(statearr_17593_19388[(2)] = null);

(statearr_17593_19388[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17580 === (9))){
var inst_17559 = (state_17579[(8)]);
var state_17579__$1 = state_17579;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17579__$1,(11),out,inst_17559);
} else {
if((state_val_17580 === (5))){
var inst_17559 = (state_17579[(8)]);
var inst_17556 = (state_17579[(7)]);
var inst_17563 = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(inst_17559,inst_17556);
var state_17579__$1 = state_17579;
if(inst_17563){
var statearr_17596_19394 = state_17579__$1;
(statearr_17596_19394[(1)] = (8));

} else {
var statearr_17597_19395 = state_17579__$1;
(statearr_17597_19395[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17580 === (10))){
var inst_17571 = (state_17579[(2)]);
var state_17579__$1 = state_17579;
var statearr_17598_19396 = state_17579__$1;
(statearr_17598_19396[(2)] = inst_17571);

(statearr_17598_19396[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17580 === (8))){
var inst_17556 = (state_17579[(7)]);
var tmp17595 = inst_17556;
var inst_17556__$1 = tmp17595;
var state_17579__$1 = (function (){var statearr_17602 = state_17579;
(statearr_17602[(7)] = inst_17556__$1);

return statearr_17602;
})();
var statearr_17603_19400 = state_17579__$1;
(statearr_17603_19400[(2)] = null);

(statearr_17603_19400[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_17606 = [null,null,null,null,null,null,null,null,null,null,null];
(statearr_17606[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_17606[(1)] = (1));

return statearr_17606;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_17579){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_17579);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e17607){var ex__14017__auto__ = e17607;
var statearr_17608_19407 = state_17579;
(statearr_17608_19407[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_17579[(4)]))){
var statearr_17610_19408 = state_17579;
(statearr_17610_19408[(1)] = cljs.core.first((state_17579[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__19416 = state_17579;
state_17579 = G__19416;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_17579){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_17579);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_17612 = f__14504__auto__();
(statearr_17612[(6)] = c__14503__auto___19363);

return statearr_17612;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return out;
}));

(cljs.core.async.unique.cljs$lang$maxFixedArity = 2);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.partition = (function cljs$core$async$partition(var_args){
var G__17628 = arguments.length;
switch (G__17628) {
case 2:
return cljs.core.async.partition.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.partition.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.partition.cljs$core$IFn$_invoke$arity$2 = (function (n,ch){
return cljs.core.async.partition.cljs$core$IFn$_invoke$arity$3(n,ch,null);
}));

(cljs.core.async.partition.cljs$core$IFn$_invoke$arity$3 = (function (n,ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__14503__auto___19429 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_17673){
var state_val_17674 = (state_17673[(1)]);
if((state_val_17674 === (7))){
var inst_17669 = (state_17673[(2)]);
var state_17673__$1 = state_17673;
var statearr_17675_19433 = state_17673__$1;
(statearr_17675_19433[(2)] = inst_17669);

(statearr_17675_19433[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (1))){
var inst_17631 = (new Array(n));
var inst_17632 = inst_17631;
var inst_17633 = (0);
var state_17673__$1 = (function (){var statearr_17676 = state_17673;
(statearr_17676[(7)] = inst_17632);

(statearr_17676[(8)] = inst_17633);

return statearr_17676;
})();
var statearr_17677_19439 = state_17673__$1;
(statearr_17677_19439[(2)] = null);

(statearr_17677_19439[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (4))){
var inst_17636 = (state_17673[(9)]);
var inst_17636__$1 = (state_17673[(2)]);
var inst_17639 = (inst_17636__$1 == null);
var inst_17640 = cljs.core.not(inst_17639);
var state_17673__$1 = (function (){var statearr_17691 = state_17673;
(statearr_17691[(9)] = inst_17636__$1);

return statearr_17691;
})();
if(inst_17640){
var statearr_17692_19445 = state_17673__$1;
(statearr_17692_19445[(1)] = (5));

} else {
var statearr_17693_19446 = state_17673__$1;
(statearr_17693_19446[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (15))){
var inst_17663 = (state_17673[(2)]);
var state_17673__$1 = state_17673;
var statearr_17694_19447 = state_17673__$1;
(statearr_17694_19447[(2)] = inst_17663);

(statearr_17694_19447[(1)] = (14));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (13))){
var state_17673__$1 = state_17673;
var statearr_17697_19448 = state_17673__$1;
(statearr_17697_19448[(2)] = null);

(statearr_17697_19448[(1)] = (14));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (6))){
var inst_17633 = (state_17673[(8)]);
var inst_17659 = (inst_17633 > (0));
var state_17673__$1 = state_17673;
if(cljs.core.truth_(inst_17659)){
var statearr_17698_19451 = state_17673__$1;
(statearr_17698_19451[(1)] = (12));

} else {
var statearr_17699_19452 = state_17673__$1;
(statearr_17699_19452[(1)] = (13));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (3))){
var inst_17671 = (state_17673[(2)]);
var state_17673__$1 = state_17673;
return cljs.core.async.impl.ioc_helpers.return_chan(state_17673__$1,inst_17671);
} else {
if((state_val_17674 === (12))){
var inst_17632 = (state_17673[(7)]);
var inst_17661 = cljs.core.vec(inst_17632);
var state_17673__$1 = state_17673;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17673__$1,(15),out,inst_17661);
} else {
if((state_val_17674 === (2))){
var state_17673__$1 = state_17673;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_17673__$1,(4),ch);
} else {
if((state_val_17674 === (11))){
var inst_17652 = (state_17673[(2)]);
var inst_17653 = (new Array(n));
var inst_17632 = inst_17653;
var inst_17633 = (0);
var state_17673__$1 = (function (){var statearr_17702 = state_17673;
(statearr_17702[(10)] = inst_17652);

(statearr_17702[(7)] = inst_17632);

(statearr_17702[(8)] = inst_17633);

return statearr_17702;
})();
var statearr_17703_19456 = state_17673__$1;
(statearr_17703_19456[(2)] = null);

(statearr_17703_19456[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (9))){
var inst_17632 = (state_17673[(7)]);
var inst_17650 = cljs.core.vec(inst_17632);
var state_17673__$1 = state_17673;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17673__$1,(11),out,inst_17650);
} else {
if((state_val_17674 === (5))){
var inst_17632 = (state_17673[(7)]);
var inst_17633 = (state_17673[(8)]);
var inst_17636 = (state_17673[(9)]);
var inst_17644 = (state_17673[(11)]);
var inst_17643 = (inst_17632[inst_17633] = inst_17636);
var inst_17644__$1 = (inst_17633 + (1));
var inst_17645 = (inst_17644__$1 < n);
var state_17673__$1 = (function (){var statearr_17713 = state_17673;
(statearr_17713[(12)] = inst_17643);

(statearr_17713[(11)] = inst_17644__$1);

return statearr_17713;
})();
if(cljs.core.truth_(inst_17645)){
var statearr_17714_19459 = state_17673__$1;
(statearr_17714_19459[(1)] = (8));

} else {
var statearr_17719_19460 = state_17673__$1;
(statearr_17719_19460[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (14))){
var inst_17666 = (state_17673[(2)]);
var inst_17667 = cljs.core.async.close_BANG_(out);
var state_17673__$1 = (function (){var statearr_17721 = state_17673;
(statearr_17721[(13)] = inst_17666);

return statearr_17721;
})();
var statearr_17722_19462 = state_17673__$1;
(statearr_17722_19462[(2)] = inst_17667);

(statearr_17722_19462[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (10))){
var inst_17656 = (state_17673[(2)]);
var state_17673__$1 = state_17673;
var statearr_17723_19463 = state_17673__$1;
(statearr_17723_19463[(2)] = inst_17656);

(statearr_17723_19463[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17674 === (8))){
var inst_17632 = (state_17673[(7)]);
var inst_17644 = (state_17673[(11)]);
var tmp17720 = inst_17632;
var inst_17632__$1 = tmp17720;
var inst_17633 = inst_17644;
var state_17673__$1 = (function (){var statearr_17724 = state_17673;
(statearr_17724[(7)] = inst_17632__$1);

(statearr_17724[(8)] = inst_17633);

return statearr_17724;
})();
var statearr_17725_19468 = state_17673__$1;
(statearr_17725_19468[(2)] = null);

(statearr_17725_19468[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_17735 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_17735[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_17735[(1)] = (1));

return statearr_17735;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_17673){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_17673);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e17736){var ex__14017__auto__ = e17736;
var statearr_17737_19472 = state_17673;
(statearr_17737_19472[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_17673[(4)]))){
var statearr_17738_19473 = state_17673;
(statearr_17738_19473[(1)] = cljs.core.first((state_17673[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__19477 = state_17673;
state_17673 = G__19477;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_17673){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_17673);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_17750 = f__14504__auto__();
(statearr_17750[(6)] = c__14503__auto___19429);

return statearr_17750;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return out;
}));

(cljs.core.async.partition.cljs$lang$maxFixedArity = 3);

/**
 * Deprecated - this function will be removed. Use transducer instead
 */
cljs.core.async.partition_by = (function cljs$core$async$partition_by(var_args){
var G__17760 = arguments.length;
switch (G__17760) {
case 2:
return cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$2((arguments[(0)]),(arguments[(1)]));

break;
case 3:
return cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$3((arguments[(0)]),(arguments[(1)]),(arguments[(2)]));

break;
default:
throw (new Error(["Invalid arity: ",arguments.length].join("")));

}
});

(cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$2 = (function (f,ch){
return cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$3(f,ch,null);
}));

(cljs.core.async.partition_by.cljs$core$IFn$_invoke$arity$3 = (function (f,ch,buf_or_n){
var out = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1(buf_or_n);
var c__14503__auto___19486 = cljs.core.async.chan.cljs$core$IFn$_invoke$arity$1((1));
cljs.core.async.impl.dispatch.run((function (){
var f__14504__auto__ = (function (){var switch__14013__auto__ = (function (state_17812){
var state_val_17813 = (state_17812[(1)]);
if((state_val_17813 === (7))){
var inst_17808 = (state_17812[(2)]);
var state_17812__$1 = state_17812;
var statearr_17818_19487 = state_17812__$1;
(statearr_17818_19487[(2)] = inst_17808);

(statearr_17818_19487[(1)] = (3));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (1))){
var inst_17764 = [];
var inst_17765 = inst_17764;
var inst_17766 = new cljs.core.Keyword("cljs.core.async","nothing","cljs.core.async/nothing",-69252123);
var state_17812__$1 = (function (){var statearr_17819 = state_17812;
(statearr_17819[(7)] = inst_17765);

(statearr_17819[(8)] = inst_17766);

return statearr_17819;
})();
var statearr_17821_19490 = state_17812__$1;
(statearr_17821_19490[(2)] = null);

(statearr_17821_19490[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (4))){
var inst_17769 = (state_17812[(9)]);
var inst_17769__$1 = (state_17812[(2)]);
var inst_17770 = (inst_17769__$1 == null);
var inst_17771 = cljs.core.not(inst_17770);
var state_17812__$1 = (function (){var statearr_17823 = state_17812;
(statearr_17823[(9)] = inst_17769__$1);

return statearr_17823;
})();
if(inst_17771){
var statearr_17824_19493 = state_17812__$1;
(statearr_17824_19493[(1)] = (5));

} else {
var statearr_17825_19494 = state_17812__$1;
(statearr_17825_19494[(1)] = (6));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (15))){
var inst_17765 = (state_17812[(7)]);
var inst_17800 = cljs.core.vec(inst_17765);
var state_17812__$1 = state_17812;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17812__$1,(18),out,inst_17800);
} else {
if((state_val_17813 === (13))){
var inst_17795 = (state_17812[(2)]);
var state_17812__$1 = state_17812;
var statearr_17826_19496 = state_17812__$1;
(statearr_17826_19496[(2)] = inst_17795);

(statearr_17826_19496[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (6))){
var inst_17765 = (state_17812[(7)]);
var inst_17797 = inst_17765.length;
var inst_17798 = (inst_17797 > (0));
var state_17812__$1 = state_17812;
if(cljs.core.truth_(inst_17798)){
var statearr_17827_19497 = state_17812__$1;
(statearr_17827_19497[(1)] = (15));

} else {
var statearr_17828_19500 = state_17812__$1;
(statearr_17828_19500[(1)] = (16));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (17))){
var inst_17805 = (state_17812[(2)]);
var inst_17806 = cljs.core.async.close_BANG_(out);
var state_17812__$1 = (function (){var statearr_17830 = state_17812;
(statearr_17830[(10)] = inst_17805);

return statearr_17830;
})();
var statearr_17831_19501 = state_17812__$1;
(statearr_17831_19501[(2)] = inst_17806);

(statearr_17831_19501[(1)] = (7));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (3))){
var inst_17810 = (state_17812[(2)]);
var state_17812__$1 = state_17812;
return cljs.core.async.impl.ioc_helpers.return_chan(state_17812__$1,inst_17810);
} else {
if((state_val_17813 === (12))){
var inst_17765 = (state_17812[(7)]);
var inst_17787 = cljs.core.vec(inst_17765);
var state_17812__$1 = state_17812;
return cljs.core.async.impl.ioc_helpers.put_BANG_(state_17812__$1,(14),out,inst_17787);
} else {
if((state_val_17813 === (2))){
var state_17812__$1 = state_17812;
return cljs.core.async.impl.ioc_helpers.take_BANG_(state_17812__$1,(4),ch);
} else {
if((state_val_17813 === (11))){
var inst_17765 = (state_17812[(7)]);
var inst_17769 = (state_17812[(9)]);
var inst_17775 = (state_17812[(11)]);
var inst_17784 = inst_17765.push(inst_17769);
var tmp17838 = inst_17765;
var inst_17765__$1 = tmp17838;
var inst_17766 = inst_17775;
var state_17812__$1 = (function (){var statearr_17849 = state_17812;
(statearr_17849[(12)] = inst_17784);

(statearr_17849[(7)] = inst_17765__$1);

(statearr_17849[(8)] = inst_17766);

return statearr_17849;
})();
var statearr_17850_19508 = state_17812__$1;
(statearr_17850_19508[(2)] = null);

(statearr_17850_19508[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (9))){
var inst_17766 = (state_17812[(8)]);
var inst_17779 = cljs.core.keyword_identical_QMARK_(inst_17766,new cljs.core.Keyword("cljs.core.async","nothing","cljs.core.async/nothing",-69252123));
var state_17812__$1 = state_17812;
var statearr_17853_19512 = state_17812__$1;
(statearr_17853_19512[(2)] = inst_17779);

(statearr_17853_19512[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (5))){
var inst_17769 = (state_17812[(9)]);
var inst_17775 = (state_17812[(11)]);
var inst_17766 = (state_17812[(8)]);
var inst_17776 = (state_17812[(13)]);
var inst_17775__$1 = (f.cljs$core$IFn$_invoke$arity$1 ? f.cljs$core$IFn$_invoke$arity$1(inst_17769) : f.call(null,inst_17769));
var inst_17776__$1 = cljs.core._EQ_.cljs$core$IFn$_invoke$arity$2(inst_17775__$1,inst_17766);
var state_17812__$1 = (function (){var statearr_17854 = state_17812;
(statearr_17854[(11)] = inst_17775__$1);

(statearr_17854[(13)] = inst_17776__$1);

return statearr_17854;
})();
if(inst_17776__$1){
var statearr_17855_19513 = state_17812__$1;
(statearr_17855_19513[(1)] = (8));

} else {
var statearr_17856_19514 = state_17812__$1;
(statearr_17856_19514[(1)] = (9));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (14))){
var inst_17769 = (state_17812[(9)]);
var inst_17775 = (state_17812[(11)]);
var inst_17789 = (state_17812[(2)]);
var inst_17790 = [];
var inst_17791 = inst_17790.push(inst_17769);
var inst_17765 = inst_17790;
var inst_17766 = inst_17775;
var state_17812__$1 = (function (){var statearr_17875 = state_17812;
(statearr_17875[(14)] = inst_17789);

(statearr_17875[(15)] = inst_17791);

(statearr_17875[(7)] = inst_17765);

(statearr_17875[(8)] = inst_17766);

return statearr_17875;
})();
var statearr_17876_19516 = state_17812__$1;
(statearr_17876_19516[(2)] = null);

(statearr_17876_19516[(1)] = (2));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (16))){
var state_17812__$1 = state_17812;
var statearr_17888_19522 = state_17812__$1;
(statearr_17888_19522[(2)] = null);

(statearr_17888_19522[(1)] = (17));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (10))){
var inst_17781 = (state_17812[(2)]);
var state_17812__$1 = state_17812;
if(cljs.core.truth_(inst_17781)){
var statearr_17889_19526 = state_17812__$1;
(statearr_17889_19526[(1)] = (11));

} else {
var statearr_17890_19531 = state_17812__$1;
(statearr_17890_19531[(1)] = (12));

}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (18))){
var inst_17802 = (state_17812[(2)]);
var state_17812__$1 = state_17812;
var statearr_17892_19536 = state_17812__$1;
(statearr_17892_19536[(2)] = inst_17802);

(statearr_17892_19536[(1)] = (17));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
if((state_val_17813 === (8))){
var inst_17776 = (state_17812[(13)]);
var state_17812__$1 = state_17812;
var statearr_17893_19543 = state_17812__$1;
(statearr_17893_19543[(2)] = inst_17776);

(statearr_17893_19543[(1)] = (10));


return new cljs.core.Keyword(null,"recur","recur",-437573268);
} else {
return null;
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
}
});
return (function() {
var cljs$core$async$state_machine__14014__auto__ = null;
var cljs$core$async$state_machine__14014__auto____0 = (function (){
var statearr_17894 = [null,null,null,null,null,null,null,null,null,null,null,null,null,null,null,null];
(statearr_17894[(0)] = cljs$core$async$state_machine__14014__auto__);

(statearr_17894[(1)] = (1));

return statearr_17894;
});
var cljs$core$async$state_machine__14014__auto____1 = (function (state_17812){
while(true){
var ret_value__14015__auto__ = (function (){try{while(true){
var result__14016__auto__ = switch__14013__auto__(state_17812);
if(cljs.core.keyword_identical_QMARK_(result__14016__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
continue;
} else {
return result__14016__auto__;
}
break;
}
}catch (e17896){var ex__14017__auto__ = e17896;
var statearr_17899_19562 = state_17812;
(statearr_17899_19562[(2)] = ex__14017__auto__);


if(cljs.core.seq((state_17812[(4)]))){
var statearr_17900_19567 = state_17812;
(statearr_17900_19567[(1)] = cljs.core.first((state_17812[(4)])));

} else {
throw ex__14017__auto__;
}

return new cljs.core.Keyword(null,"recur","recur",-437573268);
}})();
if(cljs.core.keyword_identical_QMARK_(ret_value__14015__auto__,new cljs.core.Keyword(null,"recur","recur",-437573268))){
var G__19578 = state_17812;
state_17812 = G__19578;
continue;
} else {
return ret_value__14015__auto__;
}
break;
}
});
cljs$core$async$state_machine__14014__auto__ = function(state_17812){
switch(arguments.length){
case 0:
return cljs$core$async$state_machine__14014__auto____0.call(this);
case 1:
return cljs$core$async$state_machine__14014__auto____1.call(this,state_17812);
}
throw(new Error('Invalid arity: ' + arguments.length));
};
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$0 = cljs$core$async$state_machine__14014__auto____0;
cljs$core$async$state_machine__14014__auto__.cljs$core$IFn$_invoke$arity$1 = cljs$core$async$state_machine__14014__auto____1;
return cljs$core$async$state_machine__14014__auto__;
})()
})();
var state__14505__auto__ = (function (){var statearr_17903 = f__14504__auto__();
(statearr_17903[(6)] = c__14503__auto___19486);

return statearr_17903;
})();
return cljs.core.async.impl.ioc_helpers.run_state_machine_wrapped(state__14505__auto__);
}));


return out;
}));

(cljs.core.async.partition_by.cljs$lang$maxFixedArity = 3);


//# sourceMappingURL=cljs.core.async.js.map
