# release는 R8을 켜되, 로그 정리에만 쓴다.
# 이름 바꾸기와 코드 줄이기는 하지 않는다(Pleos SDK와 직렬화가 리플렉션을 쓰므로 모든 클래스를 그대로 둔다).
-dontobfuscate
-keep class ** { *; }
-keepattributes *

# 라이브러리가 참조하지만 앱에서 쓰지 않는 선택 의존성(R8 missing_rules.txt)
-dontwarn com.google.api.client.http.GenericUrl
-dontwarn com.google.api.client.http.HttpHeaders
-dontwarn com.google.api.client.http.HttpRequest
-dontwarn com.google.api.client.http.HttpRequestFactory
-dontwarn com.google.api.client.http.HttpResponse
-dontwarn com.google.api.client.http.HttpTransport
-dontwarn com.google.api.client.http.javanet.NetHttpTransport$Builder
-dontwarn com.google.api.client.http.javanet.NetHttpTransport
-dontwarn javax.lang.model.element.Modifier
-dontwarn org.joda.time.Instant
