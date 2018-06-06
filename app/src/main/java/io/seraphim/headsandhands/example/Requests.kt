package io.seraphim.headsandhands.example

import android.util.ArrayMap
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.reactivex.*
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Cancellable
import io.reactivex.internal.schedulers.RxThreadFactory
import io.reactivex.schedulers.Schedulers
import okhttp3.*
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

object Requests {
    fun get(): RequestUrlStep = RequestBuilder("GET", null)
    fun post(body: Body): RequestUrlStep = RequestBuilder("POST", body)
    fun put(body: Body): RequestUrlStep = RequestBuilder("PUT", body)
    fun emptyPost(): RequestUrlStep = RequestBuilder("POST", EmptyBody)
    fun delete(body: Body? = null): RequestUrlStep = RequestBuilder("DELETE", body)

    @JvmField val okHttp = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    @JvmField val gson = GsonBuilder().registerTypeAdapterFactory(object : TypeAdapterFactory {
        private val cache = ArrayMap<Class<*>, TypeAdapter<*>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T> {
            return cache.getOrElse(type.rawType) {
                val adapter = createTypeAdapter(gson, type.rawType)
                cache[type.rawType] = adapter
                adapter
            } as TypeAdapter<T>
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> createTypeAdapter(gson: Gson, type: Class<T>): TypeAdapter<T> {
            return when (type) {
                City::class.java -> City.TypeAdapter(gson)
                Weather::class.java -> Weather.TypeAdapter()
                Temperature::class.java -> Temperature.TypeAdapter()
                else -> throw IllegalStateException("TypeAdapter for ${type.simpleName} not found")
            } as TypeAdapter<T>
        }
    }).create()
    @JvmField val networkScheduler = Schedulers.from(ThreadPoolExecutor(2, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(), RxThreadFactory("RxNetworkScheduler", Thread.NORM_PRIORITY)))
    @JvmField val applicationJson = MediaType.parse("application/json")
}

class RequestBuilder(private val httpMethod: String, private val body: Body?)
    : RequestUrlStep, RequestMetaStep, RequestCallStep<Any>, SingleOnSubscribe<Any>,
        ObservableOnSubscribe<Any>, FlowableOnSubscribe<Any>,
        MaybeOnSubscribe<Any>, Callback, Cancellable, Disposable {
    private var responseType: Class<out Any> = Nothing::class.java
    private var call: Call = EmptyCall
    private var urlBuilder: HttpUrlBuilder? = null
    private val headers = ArrayMap<String, String>()
    private var logRequests = true
    private var emitterAdapter: EmitterAdapter<Any> = object : EmitterAdapter<Any> {
        override fun onError(error: Exception) = false
        override fun onValue(value: Any) {}
    }

    override fun url(apiMethod: String) = url(HttpUrlBuilder(apiMethod))

    override fun url(apiMethod: String, block: HttpUrlBuilder.() -> Unit) = url(HttpUrlBuilder(apiMethod).apply(block))

    override fun url(builder: HttpUrlBuilder): RequestMetaStep {
        urlBuilder = builder
        return this
    }

    override fun logRequests(log: Boolean): RequestMetaStep {
        logRequests = log
        return this
    }

    override fun header(name: String, value: Any): RequestMetaStep {
        headers[name] = value.toString()
        return this
    }

    override fun headers(values: Map<String, String>): RequestMetaStep {
        headers.putAll(values)
        return this
    }

    override fun <R : Any> prepareForCall(responseType: KClass<R>): RequestCallStep<R> {
        this.responseType = responseType.javaObjectType
        return this as RequestCallStep<R>
    }

    override fun asSingle(scheduler: Scheduler): Single<Any> {
        return Single.create(this).subscribeOn(scheduler)
    }

    override fun asObservable(scheduler: Scheduler): Observable<Any> {
        return Observable.create(this).subscribeOn(scheduler)
    }

    override fun asFlowable(scheduler: Scheduler, mode: BackpressureStrategy): Flowable<Any> {
        return Flowable.create(this, mode).subscribeOn(scheduler)
    }

    override fun asMaybe(scheduler: Scheduler): Maybe<Any> {
        return Maybe.create(this).subscribeOn(scheduler)
    }

    override fun subscribe(emitter: SingleEmitter<Any>) = subscribe(SingleEmitterAdapter(emitter))

    override fun subscribe(emitter: ObservableEmitter<Any>) = subscribe(ObservableEmitterAdapter(emitter))

    override fun subscribe(emitter: FlowableEmitter<Any>) = subscribe(FlowableEmitterAdapter(emitter))

    override fun subscribe(emitter: MaybeEmitter<Any>) = subscribe(MaybeEmitterAdapter(emitter))

    private fun subscribe(emitter: EmitterAdapter<Any>) {
        try {
            val request = buildRequest()
            logRequest(request)
            emitterAdapter = emitter
            call = Requests.okHttp.newCall(request)
            call.enqueue(this)
        } catch (e: Exception) {
            emitter.onError(e)
        }
    }

    override fun onFailure(call: Call, error: IOException) {
        emitterAdapter.onError(error)
    }

    override fun onResponse(call: Call, response: Response) {
        try {
            if (response.isSuccessful.not()) {
                emitterAdapter.onError(ApiException(response))
                return
            }
            val jsonReader = JsonReader(response.body()?.charStream() ?: throw EmptyResponseBodyException())
            if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
                val data = Requests.gson.fromJson<Any>(jsonReader, responseType)
                response.close()
                emitterAdapter.onValue(data)
            } else emitterAdapter.onError(EmptyResponseBodyException())
        } catch (e: EOFException) {
            emitterAdapter.onError(EmptyResponseBodyException())
        } catch (e: Exception) {
            emitterAdapter.onError(e)
        }
    }

    override fun cancel() = call.cancel()
    override fun isDisposed() = call.isCanceled
    override fun dispose() = call.cancel()

    private fun buildRequest(): Request {
        return Request.Builder()
                .url((urlBuilder ?: throw IllegalArgumentException("Url builder is null. Fuck how?")).build())
                .method(httpMethod, body?.requestBody())
                .headers(buildHeaders())
                .build()
    }

    private fun buildHeaders() = Headers.of(ArrayMap<String, String>(this.headers))

    private fun logRequest(request: Request) {
        // logging
    }

    //region Classes to avoid fucking emitter checkcast
    private interface EmitterAdapter<in R> {
        fun onError(error: Exception): Boolean
        fun onValue(value: R)
    }

    private inner class SingleEmitterAdapter<in R>(private val emitter: SingleEmitter<R>) : EmitterAdapter<R> {
        init {
            emitter.setCancellable(this@RequestBuilder)
            emitter.setDisposable(this@RequestBuilder)
        }

        override fun onError(error: Exception) = emitter.tryOnError(error)
        override fun onValue(value: R) = emitter.onSuccess(value)
    }

    private inner class ObservableEmitterAdapter<in R>(private val emitter: ObservableEmitter<R>) : EmitterAdapter<R> {
        init {
            emitter.setCancellable(this@RequestBuilder)
            emitter.setDisposable(this@RequestBuilder)
        }

        override fun onError(error: Exception) = emitter.tryOnError(error)
        override fun onValue(value: R) {
            emitter.onNext(value)
            emitter.onComplete()
        }
    }

    private inner class FlowableEmitterAdapter<in R>(private val emitter: FlowableEmitter<R>) : EmitterAdapter<R> {
        init {
            emitter.setCancellable(this@RequestBuilder)
            emitter.setDisposable(this@RequestBuilder)
        }

        override fun onError(error: Exception) = emitter.tryOnError(error)
        override fun onValue(value: R) {
            emitter.onNext(value)
            emitter.onComplete()
        }
    }

    private inner class MaybeEmitterAdapter<in R>(private val emitter: MaybeEmitter<R>) : EmitterAdapter<R> {
        init {
            emitter.setCancellable(this@RequestBuilder)
            emitter.setDisposable(this@RequestBuilder)
        }

        override fun onError(error: Exception) = emitter.tryOnError(error)
        override fun onValue(value: R) = emitter.onSuccess(value)
    }
    //endregion

    private object EmptyCall : Call {
        override fun enqueue(responseCallback: Callback?) {}
        override fun isExecuted() = true
        override fun clone() = this
        override fun isCanceled() = true
        override fun cancel() {}
        override fun request() = throw IllegalAccessError("Don't use empty call!")
        override fun execute() = throw IllegalAccessError("Don't use empty call!")
    }
}

interface RequestUrlStep {
    fun url(apiMethod: String): RequestMetaStep
    fun url(apiMethod: String, block: HttpUrlBuilder.() -> Unit): RequestMetaStep
    fun url(builder: HttpUrlBuilder): RequestMetaStep
}

interface RequestMetaStep {
    fun logRequests(log: Boolean = true): RequestMetaStep
    fun header(name: String, value: Any): RequestMetaStep
    fun headers(values: Map<String, String>): RequestMetaStep
    fun <R : Any> prepareForCall(responseType: KClass<R>): RequestCallStep<R>
}

interface RequestCallStep<R> {
    fun asSingle(scheduler: Scheduler = Requests.networkScheduler): Single<R>
    fun asObservable(scheduler: Scheduler = Requests.networkScheduler): Observable<R>
    fun asFlowable(scheduler: Scheduler = Requests.networkScheduler, mode: BackpressureStrategy = BackpressureStrategy.BUFFER): Flowable<R>
    fun asMaybe(scheduler: Scheduler = Requests.networkScheduler): Maybe<R>
}

//region Url
class HttpUrlBuilder(private val url: String) {
    private var builder = HttpUrl.parse(url)?.newBuilder() ?: throw IllegalArgumentException("Invalid url $url")

    constructor(apiMethod: String, params: QueryParams) : this(apiMethod) {
        params.applyTo(builder)
    }

    fun addParam(name: String, value: String): HttpUrlBuilder {
        builder.addQueryParameter(name, value)
        return this
    }

    fun addParam(name: String, value: Int): HttpUrlBuilder {
        builder.addQueryParameter(name, value.toString())
        return this
    }

    fun addParam(name: String, value: Long): HttpUrlBuilder {
        builder.addQueryParameter(name, value.toString())
        return this
    }

    fun <T> addParam(name: String, value: List<T>): HttpUrlBuilder {
        builder.addQueryParameter(name, value.joinToString(","))
        return this
    }

    fun <T> addParam(name: String, value: Array<T>): HttpUrlBuilder {
        builder.addQueryParameter(name, value.joinToString(","))
        return this
    }

    fun queryParams(params: QueryParams): HttpUrlBuilder {
        params.applyTo(builder)
        return this
    }

    fun queryParams(params: ArrayMap<String, Any>): HttpUrlBuilder {
        params.forEach { builder.addQueryParameter(it.key, it.value.toString()) }
        return this
    }

    fun changeBaseUrl(newBaseUrl: String): HttpUrlBuilder {
        builder = HttpUrl.parse(newBaseUrl + url)?.newBuilder() ?: throw IllegalArgumentException()
        return this
    }

    fun build(): HttpUrl = builder.build()
}

open class QueryParams {
    protected val params = ArrayMap<String, String>()

    fun add(name: String, value: Any): QueryParams {
        params.put(name, value.toString())
        return this
    }

    fun applyTo(urlBuilder: HttpUrl.Builder) {
        params.entries.forEach { urlBuilder.addQueryParameter(it.key, it.value) }
    }

    fun applyTo(urlBuilder: HttpUrlBuilder) {
        params.entries.forEach { urlBuilder.addParam(it.key, it.value) }
    }
}
//endregion

//region Body
interface Body {
    companion object {
        inline fun <reified B : Any> json(model: B) = JsonBody(model, B::class.java)
    }

    fun requestBody(): RequestBody
}

class JsonBody(
        private val model: Any,
        private val type: Class<out Any>
) : Body {
    override fun requestBody() = RequestBody.create(MediaType.parse("application/json"), Requests.gson.toJson(model, type))
    override fun toString() = Requests.gson.toJson(model, type)
}

object EmptyBody : Body {
    override fun requestBody() = RequestBody.create(MediaType.parse("application/json"), "")
}
//endregion

class EmptyResponseBodyException : IllegalStateException()

class ApiException(@JvmField val response: Response) : RuntimeException(response.message())

class ApiResponse<out T>(@JvmField val response: Response, @JvmField val data: T)