package com.inhibitai.app.data.remote

import com.inhibitai.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/** The backend's REST API (all endpoints except auth require a bearer token). */
interface InhibitApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): TokenResponse

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @GET("api/v1/auth/me")
    suspend fun me(): UserDto

    @PATCH("api/v1/auth/me")
    suspend fun updateMe(@Body body: ProfileUpdate): UserDto

    @POST("api/v1/auth/change-password")
    suspend fun changePassword(@Body body: PasswordChange): Response<Unit>

    @POST("api/v1/auth/google")
    suspend fun google(@Body body: GoogleSignInRequest): TokenResponse

    @POST("api/v1/auth/password-reset/request")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequest): MessageResponse

    @POST("api/v1/auth/password-reset/confirm")
    suspend fun confirmPasswordReset(@Body body: PasswordResetConfirm): TokenResponse

    @POST("api/v1/chem/validate")
    suspend fun validate(@Body body: SmilesRequest): ValidateResponse

    @POST("api/v1/chem/depict")
    suspend fun depict(@Body body: DepictRequest): ResponseBody

    @POST("api/v1/predictions")
    suspend fun predict(@Body body: SmilesRequest): SubmissionDto

    @GET("api/v1/predictions")
    suspend fun history(@Query("limit") limit: Int = 1000): SubmissionPage

    @PATCH("api/v1/predictions/{id}")
    suspend fun updatePrediction(@Path("id") id: Long, @Body body: SubmissionPatch): SubmissionDto

    @POST("api/v1/predictions/similar")
    suspend fun similar(@Body body: SimilarRequest): List<SimilarHit>

    @DELETE("api/v1/predictions/{id}")
    suspend fun delete(@Path("id") id: Long): Response<Unit>

    @GET("api/v1/model")
    suspend fun model(): ModelInfoDto

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        fun create(baseUrl: String, token: () -> String?): InhibitApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // A hosted server that was asleep, or still loading the model, can take a while to answer.
                .readTimeout(90, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = token()?.let { chain.request().newBuilder().header("Authorization", "Bearer $it").build() }
                    chain.proceed(request ?: chain.request())
                }
                .apply {
                    if (BuildConfig.DEBUG) {
                        addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC))
                    }
                }
                .build()
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(InhibitApi::class.java)
        }
    }
}
