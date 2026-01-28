goog.provide('cljs.spec.gen.alpha');

/**
* @constructor
 * @implements {cljs.core.IDeref}
*/
cljs.spec.gen.alpha.LazyVar = (function (f,cached){
this.f = f;
this.cached = cached;
this.cljs$lang$protocol_mask$partition0$ = 32768;
this.cljs$lang$protocol_mask$partition1$ = 0;
});
(cljs.spec.gen.alpha.LazyVar.prototype.cljs$core$IDeref$_deref$arity$1 = (function (this$){
var self__ = this;
var this$__$1 = this;
if((!((self__.cached == null)))){
return self__.cached;
} else {
var x = (self__.f.cljs$core$IFn$_invoke$arity$0 ? self__.f.cljs$core$IFn$_invoke$arity$0() : self__.f.call(null));
if((x == null)){
} else {
(self__.cached = x);
}

return x;
}
}));

(cljs.spec.gen.alpha.LazyVar.getBasis = (function (){
return new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [new cljs.core.Symbol(null,"f","f",43394975,null),cljs.core.with_meta(new cljs.core.Symbol(null,"cached","cached",-1216707864,null),new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"mutable","mutable",875778266),true], null))], null);
}));

(cljs.spec.gen.alpha.LazyVar.cljs$lang$type = true);

(cljs.spec.gen.alpha.LazyVar.cljs$lang$ctorStr = "cljs.spec.gen.alpha/LazyVar");

(cljs.spec.gen.alpha.LazyVar.cljs$lang$ctorPrWriter = (function (this__5434__auto__,writer__5435__auto__,opt__5436__auto__){
return cljs.core._write(writer__5435__auto__,"cljs.spec.gen.alpha/LazyVar");
}));

/**
 * Positional factory function for cljs.spec.gen.alpha/LazyVar.
 */
cljs.spec.gen.alpha.__GT_LazyVar = (function cljs$spec$gen$alpha$__GT_LazyVar(f,cached){
return (new cljs.spec.gen.alpha.LazyVar(f,cached));
});

cljs.spec.gen.alpha.quick_check_ref = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.quick_check !== 'undefined')){
return clojure.test.check.quick_check;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check","quick-check","clojure.test.check/quick-check",-810344251,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check","quick-check","clojure.test.check/quick-check",-810344251,null)))+" never required")));
}
}),null));
cljs.spec.gen.alpha.quick_check = (function cljs$spec$gen$alpha$quick_check(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15706 = arguments.length;
var i__5877__auto___15707 = (0);
while(true){
if((i__5877__auto___15707 < len__5876__auto___15706)){
args__5882__auto__.push((arguments[i__5877__auto___15707]));

var G__15709 = (i__5877__auto___15707 + (1));
i__5877__auto___15707 = G__15709;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.quick_check.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.quick_check.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(cljs.spec.gen.alpha.quick_check_ref),args);
}));

(cljs.spec.gen.alpha.quick_check.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.quick_check.cljs$lang$applyTo = (function (seq14884){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq14884));
}));

cljs.spec.gen.alpha.for_all_STAR__ref = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.properties !== 'undefined') && (typeof clojure.test.check.properties.for_all_STAR_ !== 'undefined')){
return clojure.test.check.properties.for_all_STAR_;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.properties","for-all*","clojure.test.check.properties/for-all*",67088845,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.properties","for-all*","clojure.test.check.properties/for-all*",67088845,null)))+" never required")));
}
}),null));
/**
 * Dynamically loaded clojure.test.check.properties/for-all*.
 */
cljs.spec.gen.alpha.for_all_STAR_ = (function cljs$spec$gen$alpha$for_all_STAR_(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15720 = arguments.length;
var i__5877__auto___15721 = (0);
while(true){
if((i__5877__auto___15721 < len__5876__auto___15720)){
args__5882__auto__.push((arguments[i__5877__auto___15721]));

var G__15723 = (i__5877__auto___15721 + (1));
i__5877__auto___15721 = G__15723;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.for_all_STAR_.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.for_all_STAR_.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(cljs.spec.gen.alpha.for_all_STAR__ref),args);
}));

(cljs.spec.gen.alpha.for_all_STAR_.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.for_all_STAR_.cljs$lang$applyTo = (function (seq14931){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq14931));
}));

var g_QMARK__15726 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.generator_QMARK_ !== 'undefined')){
return clojure.test.check.generators.generator_QMARK_;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","generator?","clojure.test.check.generators/generator?",-1378210460,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","generator?","clojure.test.check.generators/generator?",-1378210460,null)))+" never required")));
}
}),null));
var g_15727 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.generate !== 'undefined')){
return clojure.test.check.generators.generate;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","generate","clojure.test.check.generators/generate",-690390711,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","generate","clojure.test.check.generators/generate",-690390711,null)))+" never required")));
}
}),null));
var mkg_15728 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.__GT_Generator !== 'undefined')){
return clojure.test.check.generators.__GT_Generator;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","->Generator","clojure.test.check.generators/->Generator",-1179475051,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","->Generator","clojure.test.check.generators/->Generator",-1179475051,null)))+" never required")));
}
}),null));
cljs.spec.gen.alpha.generator_QMARK_ = (function cljs$spec$gen$alpha$generator_QMARK_(x){
var fexpr__14958 = cljs.core.deref(g_QMARK__15726);
return (fexpr__14958.cljs$core$IFn$_invoke$arity$1 ? fexpr__14958.cljs$core$IFn$_invoke$arity$1(x) : fexpr__14958.call(null,x));
});

cljs.spec.gen.alpha.generator = (function cljs$spec$gen$alpha$generator(gfn){
var fexpr__14959 = cljs.core.deref(mkg_15728);
return (fexpr__14959.cljs$core$IFn$_invoke$arity$1 ? fexpr__14959.cljs$core$IFn$_invoke$arity$1(gfn) : fexpr__14959.call(null,gfn));
});

/**
 * Generate a single value using generator.
 */
cljs.spec.gen.alpha.generate = (function cljs$spec$gen$alpha$generate(generator){
var fexpr__14960 = cljs.core.deref(g_15727);
return (fexpr__14960.cljs$core$IFn$_invoke$arity$1 ? fexpr__14960.cljs$core$IFn$_invoke$arity$1(generator) : fexpr__14960.call(null,generator));
});
cljs.spec.gen.alpha.delay_impl = (function cljs$spec$gen$alpha$delay_impl(gfnd){
return cljs.spec.gen.alpha.generator((function (rnd,size){
var fexpr__14963 = new cljs.core.Keyword(null,"gen","gen",142575302).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(gfnd));
return (fexpr__14963.cljs$core$IFn$_invoke$arity$2 ? fexpr__14963.cljs$core$IFn$_invoke$arity$2(rnd,size) : fexpr__14963.call(null,rnd,size));
}));
});
var g__11688__auto___15745 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.hash_map !== 'undefined')){
return clojure.test.check.generators.hash_map;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","hash-map","clojure.test.check.generators/hash-map",1961346626,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","hash-map","clojure.test.check.generators/hash-map",1961346626,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/hash-map
 */
cljs.spec.gen.alpha.hash_map = (function cljs$spec$gen$alpha$hash_map(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15748 = arguments.length;
var i__5877__auto___15749 = (0);
while(true){
if((i__5877__auto___15749 < len__5876__auto___15748)){
args__5882__auto__.push((arguments[i__5877__auto___15749]));

var G__15750 = (i__5877__auto___15749 + (1));
i__5877__auto___15749 = G__15750;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.hash_map.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.hash_map.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15745),args);
}));

(cljs.spec.gen.alpha.hash_map.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.hash_map.cljs$lang$applyTo = (function (seq14974){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq14974));
}));


var g__11688__auto___15755 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.list !== 'undefined')){
return clojure.test.check.generators.list;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","list","clojure.test.check.generators/list",506971058,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","list","clojure.test.check.generators/list",506971058,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/list
 */
cljs.spec.gen.alpha.list = (function cljs$spec$gen$alpha$list(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15756 = arguments.length;
var i__5877__auto___15757 = (0);
while(true){
if((i__5877__auto___15757 < len__5876__auto___15756)){
args__5882__auto__.push((arguments[i__5877__auto___15757]));

var G__15758 = (i__5877__auto___15757 + (1));
i__5877__auto___15757 = G__15758;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.list.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.list.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15755),args);
}));

(cljs.spec.gen.alpha.list.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.list.cljs$lang$applyTo = (function (seq14983){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq14983));
}));


var g__11688__auto___15771 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.map !== 'undefined')){
return clojure.test.check.generators.map;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","map","clojure.test.check.generators/map",45738796,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","map","clojure.test.check.generators/map",45738796,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/map
 */
cljs.spec.gen.alpha.map = (function cljs$spec$gen$alpha$map(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15772 = arguments.length;
var i__5877__auto___15773 = (0);
while(true){
if((i__5877__auto___15773 < len__5876__auto___15772)){
args__5882__auto__.push((arguments[i__5877__auto___15773]));

var G__15774 = (i__5877__auto___15773 + (1));
i__5877__auto___15773 = G__15774;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.map.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.map.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15771),args);
}));

(cljs.spec.gen.alpha.map.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.map.cljs$lang$applyTo = (function (seq14994){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq14994));
}));


var g__11688__auto___15788 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.not_empty !== 'undefined')){
return clojure.test.check.generators.not_empty;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","not-empty","clojure.test.check.generators/not-empty",-876211682,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","not-empty","clojure.test.check.generators/not-empty",-876211682,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/not-empty
 */
cljs.spec.gen.alpha.not_empty = (function cljs$spec$gen$alpha$not_empty(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15806 = arguments.length;
var i__5877__auto___15807 = (0);
while(true){
if((i__5877__auto___15807 < len__5876__auto___15806)){
args__5882__auto__.push((arguments[i__5877__auto___15807]));

var G__15809 = (i__5877__auto___15807 + (1));
i__5877__auto___15807 = G__15809;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.not_empty.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.not_empty.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15788),args);
}));

(cljs.spec.gen.alpha.not_empty.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.not_empty.cljs$lang$applyTo = (function (seq15009){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15009));
}));


var g__11688__auto___15820 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.set !== 'undefined')){
return clojure.test.check.generators.set;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","set","clojure.test.check.generators/set",-1027639543,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","set","clojure.test.check.generators/set",-1027639543,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/set
 */
cljs.spec.gen.alpha.set = (function cljs$spec$gen$alpha$set(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15827 = arguments.length;
var i__5877__auto___15831 = (0);
while(true){
if((i__5877__auto___15831 < len__5876__auto___15827)){
args__5882__auto__.push((arguments[i__5877__auto___15831]));

var G__15835 = (i__5877__auto___15831 + (1));
i__5877__auto___15831 = G__15835;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.set.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.set.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15820),args);
}));

(cljs.spec.gen.alpha.set.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.set.cljs$lang$applyTo = (function (seq15022){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15022));
}));


var g__11688__auto___15857 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.vector !== 'undefined')){
return clojure.test.check.generators.vector;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","vector","clojure.test.check.generators/vector",1081775325,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","vector","clojure.test.check.generators/vector",1081775325,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/vector
 */
cljs.spec.gen.alpha.vector = (function cljs$spec$gen$alpha$vector(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15879 = arguments.length;
var i__5877__auto___15881 = (0);
while(true){
if((i__5877__auto___15881 < len__5876__auto___15879)){
args__5882__auto__.push((arguments[i__5877__auto___15881]));

var G__15887 = (i__5877__auto___15881 + (1));
i__5877__auto___15881 = G__15887;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.vector.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.vector.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15857),args);
}));

(cljs.spec.gen.alpha.vector.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.vector.cljs$lang$applyTo = (function (seq15042){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15042));
}));


var g__11688__auto___15895 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.vector_distinct !== 'undefined')){
return clojure.test.check.generators.vector_distinct;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","vector-distinct","clojure.test.check.generators/vector-distinct",1656877834,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","vector-distinct","clojure.test.check.generators/vector-distinct",1656877834,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/vector-distinct
 */
cljs.spec.gen.alpha.vector_distinct = (function cljs$spec$gen$alpha$vector_distinct(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15906 = arguments.length;
var i__5877__auto___15909 = (0);
while(true){
if((i__5877__auto___15909 < len__5876__auto___15906)){
args__5882__auto__.push((arguments[i__5877__auto___15909]));

var G__15921 = (i__5877__auto___15909 + (1));
i__5877__auto___15909 = G__15921;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.vector_distinct.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.vector_distinct.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15895),args);
}));

(cljs.spec.gen.alpha.vector_distinct.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.vector_distinct.cljs$lang$applyTo = (function (seq15065){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15065));
}));


var g__11688__auto___15940 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.fmap !== 'undefined')){
return clojure.test.check.generators.fmap;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","fmap","clojure.test.check.generators/fmap",1957997092,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","fmap","clojure.test.check.generators/fmap",1957997092,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/fmap
 */
cljs.spec.gen.alpha.fmap = (function cljs$spec$gen$alpha$fmap(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15943 = arguments.length;
var i__5877__auto___15944 = (0);
while(true){
if((i__5877__auto___15944 < len__5876__auto___15943)){
args__5882__auto__.push((arguments[i__5877__auto___15944]));

var G__15945 = (i__5877__auto___15944 + (1));
i__5877__auto___15944 = G__15945;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.fmap.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.fmap.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15940),args);
}));

(cljs.spec.gen.alpha.fmap.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.fmap.cljs$lang$applyTo = (function (seq15073){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15073));
}));


var g__11688__auto___15946 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.elements !== 'undefined')){
return clojure.test.check.generators.elements;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","elements","clojure.test.check.generators/elements",438991326,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","elements","clojure.test.check.generators/elements",438991326,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/elements
 */
cljs.spec.gen.alpha.elements = (function cljs$spec$gen$alpha$elements(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15952 = arguments.length;
var i__5877__auto___15953 = (0);
while(true){
if((i__5877__auto___15953 < len__5876__auto___15952)){
args__5882__auto__.push((arguments[i__5877__auto___15953]));

var G__15955 = (i__5877__auto___15953 + (1));
i__5877__auto___15953 = G__15955;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.elements.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.elements.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15946),args);
}));

(cljs.spec.gen.alpha.elements.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.elements.cljs$lang$applyTo = (function (seq15076){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15076));
}));


var g__11688__auto___15959 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.bind !== 'undefined')){
return clojure.test.check.generators.bind;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","bind","clojure.test.check.generators/bind",-361313906,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","bind","clojure.test.check.generators/bind",-361313906,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/bind
 */
cljs.spec.gen.alpha.bind = (function cljs$spec$gen$alpha$bind(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15962 = arguments.length;
var i__5877__auto___15963 = (0);
while(true){
if((i__5877__auto___15963 < len__5876__auto___15962)){
args__5882__auto__.push((arguments[i__5877__auto___15963]));

var G__15965 = (i__5877__auto___15963 + (1));
i__5877__auto___15963 = G__15965;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.bind.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.bind.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15959),args);
}));

(cljs.spec.gen.alpha.bind.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.bind.cljs$lang$applyTo = (function (seq15082){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15082));
}));


var g__11688__auto___15968 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.choose !== 'undefined')){
return clojure.test.check.generators.choose;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","choose","clojure.test.check.generators/choose",909997832,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","choose","clojure.test.check.generators/choose",909997832,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/choose
 */
cljs.spec.gen.alpha.choose = (function cljs$spec$gen$alpha$choose(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15972 = arguments.length;
var i__5877__auto___15974 = (0);
while(true){
if((i__5877__auto___15974 < len__5876__auto___15972)){
args__5882__auto__.push((arguments[i__5877__auto___15974]));

var G__15975 = (i__5877__auto___15974 + (1));
i__5877__auto___15974 = G__15975;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.choose.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.choose.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15968),args);
}));

(cljs.spec.gen.alpha.choose.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.choose.cljs$lang$applyTo = (function (seq15090){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15090));
}));


var g__11688__auto___15979 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.one_of !== 'undefined')){
return clojure.test.check.generators.one_of;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","one-of","clojure.test.check.generators/one-of",-183339191,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","one-of","clojure.test.check.generators/one-of",-183339191,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/one-of
 */
cljs.spec.gen.alpha.one_of = (function cljs$spec$gen$alpha$one_of(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15985 = arguments.length;
var i__5877__auto___15987 = (0);
while(true){
if((i__5877__auto___15987 < len__5876__auto___15985)){
args__5882__auto__.push((arguments[i__5877__auto___15987]));

var G__15989 = (i__5877__auto___15987 + (1));
i__5877__auto___15987 = G__15989;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15979),args);
}));

(cljs.spec.gen.alpha.one_of.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.one_of.cljs$lang$applyTo = (function (seq15100){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15100));
}));


var g__11688__auto___15995 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.such_that !== 'undefined')){
return clojure.test.check.generators.such_that;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","such-that","clojure.test.check.generators/such-that",-1754178732,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","such-that","clojure.test.check.generators/such-that",-1754178732,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/such-that
 */
cljs.spec.gen.alpha.such_that = (function cljs$spec$gen$alpha$such_that(var_args){
var args__5882__auto__ = [];
var len__5876__auto___15997 = arguments.length;
var i__5877__auto___15998 = (0);
while(true){
if((i__5877__auto___15998 < len__5876__auto___15997)){
args__5882__auto__.push((arguments[i__5877__auto___15998]));

var G__16000 = (i__5877__auto___15998 + (1));
i__5877__auto___15998 = G__16000;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.such_that.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.such_that.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___15995),args);
}));

(cljs.spec.gen.alpha.such_that.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.such_that.cljs$lang$applyTo = (function (seq15105){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15105));
}));


var g__11688__auto___16006 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.tuple !== 'undefined')){
return clojure.test.check.generators.tuple;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","tuple","clojure.test.check.generators/tuple",-143711557,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","tuple","clojure.test.check.generators/tuple",-143711557,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/tuple
 */
cljs.spec.gen.alpha.tuple = (function cljs$spec$gen$alpha$tuple(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16012 = arguments.length;
var i__5877__auto___16013 = (0);
while(true){
if((i__5877__auto___16013 < len__5876__auto___16012)){
args__5882__auto__.push((arguments[i__5877__auto___16013]));

var G__16014 = (i__5877__auto___16013 + (1));
i__5877__auto___16013 = G__16014;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.tuple.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.tuple.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___16006),args);
}));

(cljs.spec.gen.alpha.tuple.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.tuple.cljs$lang$applyTo = (function (seq15118){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15118));
}));


var g__11688__auto___16016 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.sample !== 'undefined')){
return clojure.test.check.generators.sample;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","sample","clojure.test.check.generators/sample",-382944992,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","sample","clojure.test.check.generators/sample",-382944992,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/sample
 */
cljs.spec.gen.alpha.sample = (function cljs$spec$gen$alpha$sample(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16020 = arguments.length;
var i__5877__auto___16022 = (0);
while(true){
if((i__5877__auto___16022 < len__5876__auto___16020)){
args__5882__auto__.push((arguments[i__5877__auto___16022]));

var G__16023 = (i__5877__auto___16022 + (1));
i__5877__auto___16022 = G__16023;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.sample.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.sample.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___16016),args);
}));

(cljs.spec.gen.alpha.sample.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.sample.cljs$lang$applyTo = (function (seq15132){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15132));
}));


var g__11688__auto___16029 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.return$ !== 'undefined')){
return clojure.test.check.generators.return$;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","return","clojure.test.check.generators/return",1744522038,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","return","clojure.test.check.generators/return",1744522038,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/return
 */
cljs.spec.gen.alpha.return$ = (function cljs$spec$gen$alpha$return(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16031 = arguments.length;
var i__5877__auto___16033 = (0);
while(true){
if((i__5877__auto___16033 < len__5876__auto___16031)){
args__5882__auto__.push((arguments[i__5877__auto___16033]));

var G__16034 = (i__5877__auto___16033 + (1));
i__5877__auto___16033 = G__16034;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.return$.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.return$.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___16029),args);
}));

(cljs.spec.gen.alpha.return$.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.return$.cljs$lang$applyTo = (function (seq15152){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15152));
}));


var g__11688__auto___16036 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.large_integer_STAR_ !== 'undefined')){
return clojure.test.check.generators.large_integer_STAR_;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","large-integer*","clojure.test.check.generators/large-integer*",-437830670,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","large-integer*","clojure.test.check.generators/large-integer*",-437830670,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/large-integer*
 */
cljs.spec.gen.alpha.large_integer_STAR_ = (function cljs$spec$gen$alpha$large_integer_STAR_(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16038 = arguments.length;
var i__5877__auto___16040 = (0);
while(true){
if((i__5877__auto___16040 < len__5876__auto___16038)){
args__5882__auto__.push((arguments[i__5877__auto___16040]));

var G__16041 = (i__5877__auto___16040 + (1));
i__5877__auto___16040 = G__16041;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.large_integer_STAR_.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.large_integer_STAR_.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___16036),args);
}));

(cljs.spec.gen.alpha.large_integer_STAR_.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.large_integer_STAR_.cljs$lang$applyTo = (function (seq15174){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15174));
}));


var g__11688__auto___16045 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.double_STAR_ !== 'undefined')){
return clojure.test.check.generators.double_STAR_;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","double*","clojure.test.check.generators/double*",841542265,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","double*","clojure.test.check.generators/double*",841542265,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/double*
 */
cljs.spec.gen.alpha.double_STAR_ = (function cljs$spec$gen$alpha$double_STAR_(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16050 = arguments.length;
var i__5877__auto___16051 = (0);
while(true){
if((i__5877__auto___16051 < len__5876__auto___16050)){
args__5882__auto__.push((arguments[i__5877__auto___16051]));

var G__16052 = (i__5877__auto___16051 + (1));
i__5877__auto___16051 = G__16052;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.double_STAR_.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.double_STAR_.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___16045),args);
}));

(cljs.spec.gen.alpha.double_STAR_.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.double_STAR_.cljs$lang$applyTo = (function (seq15205){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15205));
}));


var g__11688__auto___16056 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.frequency !== 'undefined')){
return clojure.test.check.generators.frequency;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","frequency","clojure.test.check.generators/frequency",2090703177,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","frequency","clojure.test.check.generators/frequency",2090703177,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/frequency
 */
cljs.spec.gen.alpha.frequency = (function cljs$spec$gen$alpha$frequency(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16061 = arguments.length;
var i__5877__auto___16065 = (0);
while(true){
if((i__5877__auto___16065 < len__5876__auto___16061)){
args__5882__auto__.push((arguments[i__5877__auto___16065]));

var G__16066 = (i__5877__auto___16065 + (1));
i__5877__auto___16065 = G__16066;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.frequency.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.frequency.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___16056),args);
}));

(cljs.spec.gen.alpha.frequency.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.frequency.cljs$lang$applyTo = (function (seq15220){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15220));
}));


var g__11688__auto___16071 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.shuffle !== 'undefined')){
return clojure.test.check.generators.shuffle;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","shuffle","clojure.test.check.generators/shuffle",1032401055,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","shuffle","clojure.test.check.generators/shuffle",1032401055,null)))+" never required")));
}
}),null));
/**
 * Lazy loaded version of clojure.test.check.generators/shuffle
 */
cljs.spec.gen.alpha.shuffle = (function cljs$spec$gen$alpha$shuffle(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16074 = arguments.length;
var i__5877__auto___16075 = (0);
while(true){
if((i__5877__auto___16075 < len__5876__auto___16074)){
args__5882__auto__.push((arguments[i__5877__auto___16075]));

var G__16077 = (i__5877__auto___16075 + (1));
i__5877__auto___16075 = G__16077;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.shuffle.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.shuffle.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(g__11688__auto___16071),args);
}));

(cljs.spec.gen.alpha.shuffle.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.shuffle.cljs$lang$applyTo = (function (seq15257){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15257));
}));

var g__11693__auto___16082 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.any !== 'undefined')){
return clojure.test.check.generators.any;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","any","clojure.test.check.generators/any",1883743710,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","any","clojure.test.check.generators/any",1883743710,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/any
 */
cljs.spec.gen.alpha.any = (function cljs$spec$gen$alpha$any(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16087 = arguments.length;
var i__5877__auto___16088 = (0);
while(true){
if((i__5877__auto___16088 < len__5876__auto___16087)){
args__5882__auto__.push((arguments[i__5877__auto___16088]));

var G__16090 = (i__5877__auto___16088 + (1));
i__5877__auto___16088 = G__16090;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.any.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.any.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16082);
}));

(cljs.spec.gen.alpha.any.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.any.cljs$lang$applyTo = (function (seq15272){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15272));
}));


var g__11693__auto___16094 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.any_printable !== 'undefined')){
return clojure.test.check.generators.any_printable;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","any-printable","clojure.test.check.generators/any-printable",-1570493991,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","any-printable","clojure.test.check.generators/any-printable",-1570493991,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/any-printable
 */
cljs.spec.gen.alpha.any_printable = (function cljs$spec$gen$alpha$any_printable(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16096 = arguments.length;
var i__5877__auto___16097 = (0);
while(true){
if((i__5877__auto___16097 < len__5876__auto___16096)){
args__5882__auto__.push((arguments[i__5877__auto___16097]));

var G__16098 = (i__5877__auto___16097 + (1));
i__5877__auto___16097 = G__16098;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.any_printable.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.any_printable.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16094);
}));

(cljs.spec.gen.alpha.any_printable.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.any_printable.cljs$lang$applyTo = (function (seq15279){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15279));
}));


var g__11693__auto___16103 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.boolean$ !== 'undefined')){
return clojure.test.check.generators.boolean$;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","boolean","clojure.test.check.generators/boolean",1586992347,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","boolean","clojure.test.check.generators/boolean",1586992347,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/boolean
 */
cljs.spec.gen.alpha.boolean$ = (function cljs$spec$gen$alpha$boolean(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16108 = arguments.length;
var i__5877__auto___16109 = (0);
while(true){
if((i__5877__auto___16109 < len__5876__auto___16108)){
args__5882__auto__.push((arguments[i__5877__auto___16109]));

var G__16111 = (i__5877__auto___16109 + (1));
i__5877__auto___16109 = G__16111;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.boolean$.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.boolean$.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16103);
}));

(cljs.spec.gen.alpha.boolean$.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.boolean$.cljs$lang$applyTo = (function (seq15316){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15316));
}));


var g__11693__auto___16115 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.char$ !== 'undefined')){
return clojure.test.check.generators.char$;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","char","clojure.test.check.generators/char",-1426343459,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","char","clojure.test.check.generators/char",-1426343459,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/char
 */
cljs.spec.gen.alpha.char$ = (function cljs$spec$gen$alpha$char(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16122 = arguments.length;
var i__5877__auto___16123 = (0);
while(true){
if((i__5877__auto___16123 < len__5876__auto___16122)){
args__5882__auto__.push((arguments[i__5877__auto___16123]));

var G__16124 = (i__5877__auto___16123 + (1));
i__5877__auto___16123 = G__16124;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.char$.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.char$.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16115);
}));

(cljs.spec.gen.alpha.char$.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.char$.cljs$lang$applyTo = (function (seq15356){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15356));
}));


var g__11693__auto___16126 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.char_alpha !== 'undefined')){
return clojure.test.check.generators.char_alpha;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","char-alpha","clojure.test.check.generators/char-alpha",615785796,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","char-alpha","clojure.test.check.generators/char-alpha",615785796,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/char-alpha
 */
cljs.spec.gen.alpha.char_alpha = (function cljs$spec$gen$alpha$char_alpha(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16128 = arguments.length;
var i__5877__auto___16130 = (0);
while(true){
if((i__5877__auto___16130 < len__5876__auto___16128)){
args__5882__auto__.push((arguments[i__5877__auto___16130]));

var G__16132 = (i__5877__auto___16130 + (1));
i__5877__auto___16130 = G__16132;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.char_alpha.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.char_alpha.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16126);
}));

(cljs.spec.gen.alpha.char_alpha.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.char_alpha.cljs$lang$applyTo = (function (seq15369){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15369));
}));


var g__11693__auto___16138 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.char_alphanumeric !== 'undefined')){
return clojure.test.check.generators.char_alphanumeric;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","char-alphanumeric","clojure.test.check.generators/char-alphanumeric",1383091431,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","char-alphanumeric","clojure.test.check.generators/char-alphanumeric",1383091431,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/char-alphanumeric
 */
cljs.spec.gen.alpha.char_alphanumeric = (function cljs$spec$gen$alpha$char_alphanumeric(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16142 = arguments.length;
var i__5877__auto___16143 = (0);
while(true){
if((i__5877__auto___16143 < len__5876__auto___16142)){
args__5882__auto__.push((arguments[i__5877__auto___16143]));

var G__16145 = (i__5877__auto___16143 + (1));
i__5877__auto___16143 = G__16145;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.char_alphanumeric.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.char_alphanumeric.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16138);
}));

(cljs.spec.gen.alpha.char_alphanumeric.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.char_alphanumeric.cljs$lang$applyTo = (function (seq15380){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15380));
}));


var g__11693__auto___16148 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.char_ascii !== 'undefined')){
return clojure.test.check.generators.char_ascii;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","char-ascii","clojure.test.check.generators/char-ascii",-899908538,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","char-ascii","clojure.test.check.generators/char-ascii",-899908538,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/char-ascii
 */
cljs.spec.gen.alpha.char_ascii = (function cljs$spec$gen$alpha$char_ascii(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16151 = arguments.length;
var i__5877__auto___16152 = (0);
while(true){
if((i__5877__auto___16152 < len__5876__auto___16151)){
args__5882__auto__.push((arguments[i__5877__auto___16152]));

var G__16154 = (i__5877__auto___16152 + (1));
i__5877__auto___16152 = G__16154;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.char_ascii.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.char_ascii.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16148);
}));

(cljs.spec.gen.alpha.char_ascii.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.char_ascii.cljs$lang$applyTo = (function (seq15391){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15391));
}));


var g__11693__auto___16158 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.double$ !== 'undefined')){
return clojure.test.check.generators.double$;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","double","clojure.test.check.generators/double",668331090,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","double","clojure.test.check.generators/double",668331090,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/double
 */
cljs.spec.gen.alpha.double$ = (function cljs$spec$gen$alpha$double(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16165 = arguments.length;
var i__5877__auto___16167 = (0);
while(true){
if((i__5877__auto___16167 < len__5876__auto___16165)){
args__5882__auto__.push((arguments[i__5877__auto___16167]));

var G__16169 = (i__5877__auto___16167 + (1));
i__5877__auto___16167 = G__16169;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.double$.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.double$.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16158);
}));

(cljs.spec.gen.alpha.double$.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.double$.cljs$lang$applyTo = (function (seq15401){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15401));
}));


var g__11693__auto___16171 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.int$ !== 'undefined')){
return clojure.test.check.generators.int$;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","int","clojure.test.check.generators/int",1756228469,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","int","clojure.test.check.generators/int",1756228469,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/int
 */
cljs.spec.gen.alpha.int$ = (function cljs$spec$gen$alpha$int(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16172 = arguments.length;
var i__5877__auto___16173 = (0);
while(true){
if((i__5877__auto___16173 < len__5876__auto___16172)){
args__5882__auto__.push((arguments[i__5877__auto___16173]));

var G__16174 = (i__5877__auto___16173 + (1));
i__5877__auto___16173 = G__16174;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.int$.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.int$.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16171);
}));

(cljs.spec.gen.alpha.int$.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.int$.cljs$lang$applyTo = (function (seq15407){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15407));
}));


var g__11693__auto___16181 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.keyword !== 'undefined')){
return clojure.test.check.generators.keyword;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","keyword","clojure.test.check.generators/keyword",24530530,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","keyword","clojure.test.check.generators/keyword",24530530,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/keyword
 */
cljs.spec.gen.alpha.keyword = (function cljs$spec$gen$alpha$keyword(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16185 = arguments.length;
var i__5877__auto___16186 = (0);
while(true){
if((i__5877__auto___16186 < len__5876__auto___16185)){
args__5882__auto__.push((arguments[i__5877__auto___16186]));

var G__16187 = (i__5877__auto___16186 + (1));
i__5877__auto___16186 = G__16187;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.keyword.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.keyword.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16181);
}));

(cljs.spec.gen.alpha.keyword.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.keyword.cljs$lang$applyTo = (function (seq15410){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15410));
}));


var g__11693__auto___16189 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.keyword_ns !== 'undefined')){
return clojure.test.check.generators.keyword_ns;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","keyword-ns","clojure.test.check.generators/keyword-ns",-1492628482,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","keyword-ns","clojure.test.check.generators/keyword-ns",-1492628482,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/keyword-ns
 */
cljs.spec.gen.alpha.keyword_ns = (function cljs$spec$gen$alpha$keyword_ns(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16194 = arguments.length;
var i__5877__auto___16195 = (0);
while(true){
if((i__5877__auto___16195 < len__5876__auto___16194)){
args__5882__auto__.push((arguments[i__5877__auto___16195]));

var G__16198 = (i__5877__auto___16195 + (1));
i__5877__auto___16195 = G__16198;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.keyword_ns.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.keyword_ns.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16189);
}));

(cljs.spec.gen.alpha.keyword_ns.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.keyword_ns.cljs$lang$applyTo = (function (seq15441){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15441));
}));


var g__11693__auto___16200 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.large_integer !== 'undefined')){
return clojure.test.check.generators.large_integer;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","large-integer","clojure.test.check.generators/large-integer",-865967138,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","large-integer","clojure.test.check.generators/large-integer",-865967138,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/large-integer
 */
cljs.spec.gen.alpha.large_integer = (function cljs$spec$gen$alpha$large_integer(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16203 = arguments.length;
var i__5877__auto___16204 = (0);
while(true){
if((i__5877__auto___16204 < len__5876__auto___16203)){
args__5882__auto__.push((arguments[i__5877__auto___16204]));

var G__16205 = (i__5877__auto___16204 + (1));
i__5877__auto___16204 = G__16205;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.large_integer.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.large_integer.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16200);
}));

(cljs.spec.gen.alpha.large_integer.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.large_integer.cljs$lang$applyTo = (function (seq15457){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15457));
}));


var g__11693__auto___16215 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.ratio !== 'undefined')){
return clojure.test.check.generators.ratio;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","ratio","clojure.test.check.generators/ratio",1540966915,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","ratio","clojure.test.check.generators/ratio",1540966915,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/ratio
 */
cljs.spec.gen.alpha.ratio = (function cljs$spec$gen$alpha$ratio(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16217 = arguments.length;
var i__5877__auto___16218 = (0);
while(true){
if((i__5877__auto___16218 < len__5876__auto___16217)){
args__5882__auto__.push((arguments[i__5877__auto___16218]));

var G__16219 = (i__5877__auto___16218 + (1));
i__5877__auto___16218 = G__16219;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.ratio.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.ratio.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16215);
}));

(cljs.spec.gen.alpha.ratio.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.ratio.cljs$lang$applyTo = (function (seq15463){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15463));
}));


var g__11693__auto___16221 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.simple_type !== 'undefined')){
return clojure.test.check.generators.simple_type;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","simple-type","clojure.test.check.generators/simple-type",892572284,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","simple-type","clojure.test.check.generators/simple-type",892572284,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/simple-type
 */
cljs.spec.gen.alpha.simple_type = (function cljs$spec$gen$alpha$simple_type(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16225 = arguments.length;
var i__5877__auto___16226 = (0);
while(true){
if((i__5877__auto___16226 < len__5876__auto___16225)){
args__5882__auto__.push((arguments[i__5877__auto___16226]));

var G__16227 = (i__5877__auto___16226 + (1));
i__5877__auto___16226 = G__16227;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.simple_type.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.simple_type.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16221);
}));

(cljs.spec.gen.alpha.simple_type.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.simple_type.cljs$lang$applyTo = (function (seq15476){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15476));
}));


var g__11693__auto___16229 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.simple_type_printable !== 'undefined')){
return clojure.test.check.generators.simple_type_printable;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","simple-type-printable","clojure.test.check.generators/simple-type-printable",-58489962,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","simple-type-printable","clojure.test.check.generators/simple-type-printable",-58489962,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/simple-type-printable
 */
cljs.spec.gen.alpha.simple_type_printable = (function cljs$spec$gen$alpha$simple_type_printable(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16232 = arguments.length;
var i__5877__auto___16234 = (0);
while(true){
if((i__5877__auto___16234 < len__5876__auto___16232)){
args__5882__auto__.push((arguments[i__5877__auto___16234]));

var G__16235 = (i__5877__auto___16234 + (1));
i__5877__auto___16234 = G__16235;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.simple_type_printable.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.simple_type_printable.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16229);
}));

(cljs.spec.gen.alpha.simple_type_printable.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.simple_type_printable.cljs$lang$applyTo = (function (seq15494){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15494));
}));


var g__11693__auto___16238 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.string !== 'undefined')){
return clojure.test.check.generators.string;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","string","clojure.test.check.generators/string",-1704750979,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","string","clojure.test.check.generators/string",-1704750979,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/string
 */
cljs.spec.gen.alpha.string = (function cljs$spec$gen$alpha$string(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16242 = arguments.length;
var i__5877__auto___16243 = (0);
while(true){
if((i__5877__auto___16243 < len__5876__auto___16242)){
args__5882__auto__.push((arguments[i__5877__auto___16243]));

var G__16244 = (i__5877__auto___16243 + (1));
i__5877__auto___16243 = G__16244;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.string.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.string.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16238);
}));

(cljs.spec.gen.alpha.string.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.string.cljs$lang$applyTo = (function (seq15519){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15519));
}));


var g__11693__auto___16245 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.string_ascii !== 'undefined')){
return clojure.test.check.generators.string_ascii;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","string-ascii","clojure.test.check.generators/string-ascii",-2009877640,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","string-ascii","clojure.test.check.generators/string-ascii",-2009877640,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/string-ascii
 */
cljs.spec.gen.alpha.string_ascii = (function cljs$spec$gen$alpha$string_ascii(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16248 = arguments.length;
var i__5877__auto___16249 = (0);
while(true){
if((i__5877__auto___16249 < len__5876__auto___16248)){
args__5882__auto__.push((arguments[i__5877__auto___16249]));

var G__16250 = (i__5877__auto___16249 + (1));
i__5877__auto___16249 = G__16250;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.string_ascii.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.string_ascii.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16245);
}));

(cljs.spec.gen.alpha.string_ascii.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.string_ascii.cljs$lang$applyTo = (function (seq15528){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15528));
}));


var g__11693__auto___16251 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.string_alphanumeric !== 'undefined')){
return clojure.test.check.generators.string_alphanumeric;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","string-alphanumeric","clojure.test.check.generators/string-alphanumeric",836374939,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","string-alphanumeric","clojure.test.check.generators/string-alphanumeric",836374939,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/string-alphanumeric
 */
cljs.spec.gen.alpha.string_alphanumeric = (function cljs$spec$gen$alpha$string_alphanumeric(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16256 = arguments.length;
var i__5877__auto___16257 = (0);
while(true){
if((i__5877__auto___16257 < len__5876__auto___16256)){
args__5882__auto__.push((arguments[i__5877__auto___16257]));

var G__16258 = (i__5877__auto___16257 + (1));
i__5877__auto___16257 = G__16258;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.string_alphanumeric.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.string_alphanumeric.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16251);
}));

(cljs.spec.gen.alpha.string_alphanumeric.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.string_alphanumeric.cljs$lang$applyTo = (function (seq15540){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15540));
}));


var g__11693__auto___16266 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.symbol !== 'undefined')){
return clojure.test.check.generators.symbol;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","symbol","clojure.test.check.generators/symbol",-1305461065,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","symbol","clojure.test.check.generators/symbol",-1305461065,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/symbol
 */
cljs.spec.gen.alpha.symbol = (function cljs$spec$gen$alpha$symbol(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16267 = arguments.length;
var i__5877__auto___16268 = (0);
while(true){
if((i__5877__auto___16268 < len__5876__auto___16267)){
args__5882__auto__.push((arguments[i__5877__auto___16268]));

var G__16269 = (i__5877__auto___16268 + (1));
i__5877__auto___16268 = G__16269;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.symbol.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.symbol.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16266);
}));

(cljs.spec.gen.alpha.symbol.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.symbol.cljs$lang$applyTo = (function (seq15554){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15554));
}));


var g__11693__auto___16271 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.symbol_ns !== 'undefined')){
return clojure.test.check.generators.symbol_ns;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","symbol-ns","clojure.test.check.generators/symbol-ns",-862629490,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","symbol-ns","clojure.test.check.generators/symbol-ns",-862629490,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/symbol-ns
 */
cljs.spec.gen.alpha.symbol_ns = (function cljs$spec$gen$alpha$symbol_ns(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16273 = arguments.length;
var i__5877__auto___16274 = (0);
while(true){
if((i__5877__auto___16274 < len__5876__auto___16273)){
args__5882__auto__.push((arguments[i__5877__auto___16274]));

var G__16275 = (i__5877__auto___16274 + (1));
i__5877__auto___16274 = G__16275;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.symbol_ns.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.symbol_ns.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16271);
}));

(cljs.spec.gen.alpha.symbol_ns.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.symbol_ns.cljs$lang$applyTo = (function (seq15565){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15565));
}));


var g__11693__auto___16285 = (new cljs.spec.gen.alpha.LazyVar((function (){
if((typeof clojure !== 'undefined') && (typeof clojure.test !== 'undefined') && (typeof clojure.test.check !== 'undefined') && (typeof clojure.test.check.generators !== 'undefined') && (typeof clojure.test.check.generators.uuid !== 'undefined')){
return clojure.test.check.generators.uuid;
} else {
throw (new Error((""+"Var "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(new cljs.core.Symbol("clojure.test.check.generators","uuid","clojure.test.check.generators/uuid",1589373144,null))+" does not exist, "+cljs.core.str.cljs$core$IFn$_invoke$arity$1(cljs.core.namespace(new cljs.core.Symbol("clojure.test.check.generators","uuid","clojure.test.check.generators/uuid",1589373144,null)))+" never required")));
}
}),null));
/**
 * Fn returning clojure.test.check.generators/uuid
 */
cljs.spec.gen.alpha.uuid = (function cljs$spec$gen$alpha$uuid(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16288 = arguments.length;
var i__5877__auto___16289 = (0);
while(true){
if((i__5877__auto___16289 < len__5876__auto___16288)){
args__5882__auto__.push((arguments[i__5877__auto___16289]));

var G__16290 = (i__5877__auto___16289 + (1));
i__5877__auto___16289 = G__16290;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.uuid.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.uuid.cljs$core$IFn$_invoke$arity$variadic = (function (args){
return cljs.core.deref(g__11693__auto___16285);
}));

(cljs.spec.gen.alpha.uuid.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.uuid.cljs$lang$applyTo = (function (seq15581){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15581));
}));

/**
 * Returns a generator of a sequence catenated from results of
 * gens, each of which should generate something sequential.
 */
cljs.spec.gen.alpha.cat = (function cljs$spec$gen$alpha$cat(var_args){
var args__5882__auto__ = [];
var len__5876__auto___16297 = arguments.length;
var i__5877__auto___16298 = (0);
while(true){
if((i__5877__auto___16298 < len__5876__auto___16297)){
args__5882__auto__.push((arguments[i__5877__auto___16298]));

var G__16300 = (i__5877__auto___16298 + (1));
i__5877__auto___16298 = G__16300;
continue;
} else {
}
break;
}

var argseq__5883__auto__ = ((((0) < args__5882__auto__.length))?(new cljs.core.IndexedSeq(args__5882__auto__.slice((0)),(0),null)):null);
return cljs.spec.gen.alpha.cat.cljs$core$IFn$_invoke$arity$variadic(argseq__5883__auto__);
});

(cljs.spec.gen.alpha.cat.cljs$core$IFn$_invoke$arity$variadic = (function (gens){
return cljs.spec.gen.alpha.fmap.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (p1__15616_SHARP_){
return cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.core.concat,p1__15616_SHARP_);
}),cljs.core.apply.cljs$core$IFn$_invoke$arity$2(cljs.spec.gen.alpha.tuple,gens)], 0));
}));

(cljs.spec.gen.alpha.cat.cljs$lang$maxFixedArity = (0));

/** @this {Function} */
(cljs.spec.gen.alpha.cat.cljs$lang$applyTo = (function (seq15618){
var self__5862__auto__ = this;
return self__5862__auto__.cljs$core$IFn$_invoke$arity$variadic(cljs.core.seq(seq15618));
}));

cljs.spec.gen.alpha.qualified_QMARK_ = (function cljs$spec$gen$alpha$qualified_QMARK_(ident){
return (!((cljs.core.namespace(ident) == null)));
});
cljs.spec.gen.alpha.gen_builtins = (new cljs.core.Delay((function (){
var simple = cljs.spec.gen.alpha.simple_type_printable();
return cljs.core.PersistentHashMap.fromArrays([cljs.core.qualified_keyword_QMARK_,cljs.core.seq_QMARK_,cljs.core.vector_QMARK_,cljs.core.any_QMARK_,cljs.core.boolean_QMARK_,cljs.core.char_QMARK_,cljs.core.some_QMARK_,cljs.core.inst_QMARK_,cljs.core.simple_symbol_QMARK_,cljs.core.sequential_QMARK_,cljs.core.float_QMARK_,cljs.core.set_QMARK_,cljs.core.map_QMARK_,cljs.core.empty_QMARK_,cljs.core.string_QMARK_,cljs.core.uri_QMARK_,cljs.core.double_QMARK_,cljs.core.int_QMARK_,cljs.core.associative_QMARK_,cljs.core.keyword_QMARK_,cljs.core.indexed_QMARK_,cljs.core.zero_QMARK_,cljs.core.simple_keyword_QMARK_,cljs.core.neg_int_QMARK_,cljs.core.nil_QMARK_,cljs.core.ident_QMARK_,cljs.core.qualified_ident_QMARK_,cljs.core.true_QMARK_,cljs.core.integer_QMARK_,cljs.core.nat_int_QMARK_,cljs.core.pos_int_QMARK_,cljs.core.uuid_QMARK_,cljs.core.false_QMARK_,cljs.core.list_QMARK_,cljs.core.simple_ident_QMARK_,cljs.core.number_QMARK_,cljs.core.qualified_symbol_QMARK_,cljs.core.seqable_QMARK_,cljs.core.symbol_QMARK_,cljs.core.coll_QMARK_],[cljs.spec.gen.alpha.such_that.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([cljs.spec.gen.alpha.qualified_QMARK_,cljs.spec.gen.alpha.keyword_ns()], 0)),cljs.spec.gen.alpha.list.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.vector.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.return$.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([null], 0)),cljs.spec.gen.alpha.any_printable()], null)], 0)),cljs.spec.gen.alpha.boolean$(),cljs.spec.gen.alpha.char$(),cljs.spec.gen.alpha.such_that.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([cljs.core.some_QMARK_,cljs.spec.gen.alpha.any_printable()], 0)),cljs.spec.gen.alpha.fmap.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (p1__15643_SHARP_){
return (new Date(p1__15643_SHARP_));
}),cljs.spec.gen.alpha.large_integer()], 0)),cljs.spec.gen.alpha.symbol(),cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.list.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.vector.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0))], null)], 0)),cljs.spec.gen.alpha.double$(),cljs.spec.gen.alpha.set.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.map.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple,simple], 0)),cljs.spec.gen.alpha.elements.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 5, 5, cljs.core.PersistentVector.EMPTY_NODE, [null,cljs.core.List.EMPTY,cljs.core.PersistentVector.EMPTY,cljs.core.PersistentArrayMap.EMPTY,cljs.core.PersistentHashSet.EMPTY], null)], 0)),cljs.spec.gen.alpha.string_alphanumeric(),cljs.spec.gen.alpha.fmap.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(function (p1__15642_SHARP_){
return (new goog.Uri((""+"http://"+cljs.core.str.cljs$core$IFn$_invoke$arity$1(p1__15642_SHARP_)+".com")));
}),cljs.spec.gen.alpha.uuid()], 0)),cljs.spec.gen.alpha.double$(),cljs.spec.gen.alpha.large_integer(),cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.map.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple,simple], 0)),cljs.spec.gen.alpha.vector.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0))], null)], 0)),cljs.spec.gen.alpha.keyword_ns(),cljs.spec.gen.alpha.vector.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.return$.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([(0)], 0)),cljs.spec.gen.alpha.keyword(),cljs.spec.gen.alpha.large_integer_STAR_.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"max","max",61366548),(-1)], null)], 0)),cljs.spec.gen.alpha.return$.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([null], 0)),cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.keyword_ns(),cljs.spec.gen.alpha.symbol_ns()], null)], 0)),cljs.spec.gen.alpha.such_that.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([cljs.spec.gen.alpha.qualified_QMARK_,cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.keyword_ns(),cljs.spec.gen.alpha.symbol_ns()], null)], 0))], 0)),cljs.spec.gen.alpha.return$.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([true], 0)),cljs.spec.gen.alpha.large_integer(),cljs.spec.gen.alpha.large_integer_STAR_.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"min","min",444991522),(0)], null)], 0)),cljs.spec.gen.alpha.large_integer_STAR_.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentArrayMap(null, 1, [new cljs.core.Keyword(null,"min","min",444991522),(1)], null)], 0)),cljs.spec.gen.alpha.uuid(),cljs.spec.gen.alpha.return$.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([false], 0)),cljs.spec.gen.alpha.list.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.keyword(),cljs.spec.gen.alpha.symbol()], null)], 0)),cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 2, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.large_integer(),cljs.spec.gen.alpha.double$()], null)], 0)),cljs.spec.gen.alpha.such_that.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([cljs.spec.gen.alpha.qualified_QMARK_,cljs.spec.gen.alpha.symbol_ns()], 0)),cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 6, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.return$.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([null], 0)),cljs.spec.gen.alpha.list.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.vector.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.map.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple,simple], 0)),cljs.spec.gen.alpha.set.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.string_alphanumeric()], null)], 0)),cljs.spec.gen.alpha.symbol_ns(),cljs.spec.gen.alpha.one_of.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([new cljs.core.PersistentVector(null, 4, 5, cljs.core.PersistentVector.EMPTY_NODE, [cljs.spec.gen.alpha.map.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple,simple], 0)),cljs.spec.gen.alpha.list.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.vector.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0)),cljs.spec.gen.alpha.set.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([simple], 0))], null)], 0))]);
}),null));
/**
 * Given a predicate, returns a built-in generator if one exists.
 */
cljs.spec.gen.alpha.gen_for_pred = (function cljs$spec$gen$alpha$gen_for_pred(pred){
if(cljs.core.set_QMARK_(pred)){
return cljs.spec.gen.alpha.elements.cljs$core$IFn$_invoke$arity$variadic(cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([pred], 0));
} else {
return cljs.core.get.cljs$core$IFn$_invoke$arity$2(cljs.core.deref(cljs.spec.gen.alpha.gen_builtins),pred);
}
});

//# sourceMappingURL=cljs.spec.gen.alpha.js.map
