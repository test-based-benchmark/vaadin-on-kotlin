package com.github.vok.restclient

import com.github.vok.framework.VOKPlugin
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import okhttp3.*
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Converter
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.io.Reader
import java.lang.reflect.Type

/**
 * Necessary so that Retrofit supports synchronous calls and checks that Calls has been successfully executed.
 * When you register this factory, Retrofit will support calls like `@GET("users") fun getUsers(): List<String>`.
 */
object SynchronousCallSupportFactory : CallAdapter.Factory() {
    override fun get(returnType: Type, annotations: Array<Annotation>, retrofit: Retrofit): CallAdapter<*, *> {
        return object : CallAdapter<Any?, Any?> {
            override fun responseType(): Type = returnType

            override fun adapt(call: Call<Any?>): Any? {
                val result = call.execute()
                if (!result.isSuccessful) {
                    val msg = "${result.code()}: ${result.errorBody()?.string()} (${call.request().url()})"
                    if (result.code() == 404) throw FileNotFoundException(msg)
                    throw IOException(msg)
                }
                return result.body()
            }
        }
    }
}

fun Response.checkOk(): Response {
    if (!isSuccessful) {
        val msg = "${code()}: ${body()!!.string()} (${request().url()})"
        if (code() == 404) throw java.io.FileNotFoundException(msg)
        throw java.io.IOException(msg)
    }
    return this
}

/**
 * This function configures [Retrofit] for synchronous clients, so that you can have interface with methods such as
 * `@GET("users") fun getUsers(): List<String>`.
 *
 * Usage example:
 * ```
 * val client = createRetrofit("http://localhost:8080/rest").create(YourClientInterface::class.java)
 * ```
 *
 * Beware: uses [RetrofitClientVokPlugin.okHttpClient] under the hood, which contains a common executor service.
 * If you're not running VoK, don't forget to initialize it in [RetrofitClientVokPlugin.init] and don't forget to call [RetrofitClientVokPlugin.destroy].
 * This is called automatically in VoK apps.
 */
fun createRetrofit(baseUrl: String, gson: Gson = RetrofitClientVokPlugin.gson): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .callFactory(RetrofitClientVokPlugin.okHttpClient!!)
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(UnitConversionFactory)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .addCallAdapterFactory(SynchronousCallSupportFactory)
        .build()

/**
 * Destroys the [OkHttpClient] including the dispatcher, connection pool, everything. WARNING: THIS MAY AFFECT
 * OTHER http clients if they share e.g. dispatcher executor service.
 */
fun OkHttpClient.destroy() {
    dispatcher().executorService().shutdown()
    connectionPool().evictAll()
    cache()?.close()
}

/**
 * Makes sure that [okHttpClient] is properly destroyed.
 */
class RetrofitClientVokPlugin : VOKPlugin {
    override fun init() {
        if (okHttpClient == null) {
            okHttpClient = OkHttpClient()
        }
    }

    override fun destroy() {
        okHttpClient?.destroy()
        okHttpClient = null
    }

    companion object {
        var okHttpClient: OkHttpClient? = null
        var gson: Gson = GsonBuilder().create()
    }
}

/**
 * Converts the response to [Unit] (throws it away). This adds support for functions returning `Unit?`.
 */
object UnitConversionFactory : Converter.Factory() {
    override fun responseBodyConverter(type: Type, annotations: Array<Annotation>, retrofit: Retrofit): Converter<ResponseBody, *>? {
        if (type == Unit::class.java) {
            return Converter<ResponseBody, Unit> { }
        }
        return null
    }
}

/**
 * Parses [json] as a list of items with class [itemClass] and returns that.
 */
fun <T> Gson.fromJsonArray(json: String, itemClass: Class<T>): List<T> {
    val type = TypeToken.getParameterized(List::class.java, itemClass).type
    return fromJson<List<T>>(json, type)
}

/**
 * Parses JSON from [reader] as a list of items with class [itemClass] and returns that.
 */
fun <T> Gson.fromJsonArray(reader: Reader, itemClass: Class<T>): List<T> {
    val type = TypeToken.getParameterized(List::class.java, itemClass).type
    return fromJson<List<T>>(reader, type)
}

/**
 * Runs given [request] synchronously and then runs [responseBlock] with the response body. The [Response] is properly closed afterwards.
 * Only calls the block on success; uses [checkOk] to check for failure prior calling the block.
 */
fun <T> OkHttpClient.run(request: Request, responseBlock: (ResponseBody) -> T): T =
        newCall(request).execute().use {
            responseBlock(it.checkOk().body()!!)
        }

/**
 * Uses the CRUD endpoint and serves instances of given item of type [itemClass] over given [client] using given [gson].
 * Expect the CRUD endpoint to be exposed in the following manner:
 * * `GET /rest/users` returns all users
 * * `GET /rest/users/22` returns one users
 * * `POST /rest/users` will create an user
 * * `PATCH /rest/users/22` will update an user
 * * `DELETE /rest/users/22` will delete an user
 * @param baseUrl the base URL, such as `http://localhost:8080/rest/users/`, must end with a slash.
 */
class CrudClient<T>(val baseUrl: String, val itemClass: Class<T>,
                    val client: OkHttpClient = RetrofitClientVokPlugin.okHttpClient!!, val gson: Gson = RetrofitClientVokPlugin.gson) {
    init {
        require(baseUrl.endsWith("/")) { "$baseUrl must end with /" }
    }

    fun getAll(): List<T> {
        val request = Request.Builder().url(baseUrl).build()
        return client.run(request) { response -> gson.fromJsonArray(response.charStream(), itemClass) }
    }

    fun getOne(id: String): T {
        val request = Request.Builder().url("$baseUrl$id").build()
        return client.run(request) { response -> gson.fromJson(response.charStream(), itemClass) }
    }

    fun create(entity: T) {
        val body = RequestBody.create(mediaTypeJson, gson.toJson(entity))
        val request = Request.Builder().post(body).url(baseUrl).build()
        client.run(request) {}
    }

    fun update(id: String, entity: T) {
        val body = RequestBody.create(mediaTypeJson, gson.toJson(entity))
        val request = Request.Builder().patch(body).url("$baseUrl$id").build()
        client.run(request) {}
    }

    fun delete(id: String) {
        val request = Request.Builder().delete().url("$baseUrl$id").build()
        client.run(request) {}
    }

    companion object {
        val mediaTypeJson = MediaType.parse("application/json; charset=utf-8")
    }
}