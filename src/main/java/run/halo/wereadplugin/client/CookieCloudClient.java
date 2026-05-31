package run.halo.wereadplugin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * CookieCloud 客户端   
 * @author haike
 * @date 2026-04-23
 * @version 1.0.0
 */
@Slf4j
@Component
public class CookieCloudClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CookieCloudClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public Mono<String> fetchWeReadCookie(String serverUrl, String uuid, String password) {
        String url = serverUrl;
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "get/" + uuid;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Halo-WeRead-Plugin/1.0")
                .GET()
                .build();

        return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .flatMap(response -> {
                    if (response.statusCode() != 200) {
                        return Mono.error(new RuntimeException("获取 CookieCloud 失败，状态码: " + response.statusCode()));
                    }
                    try {
                        JsonNode json = objectMapper.readTree(response.body());
                        if (json.has("encrypted")) {
                            String encrypted = json.get("encrypted").asText();
                            String decryptedJsonStr = decryptCryptoJS(encrypted, uuid, password);
                            JsonNode decryptedNode = objectMapper.readTree(decryptedJsonStr);
                            
                            JsonNode cookieData = decryptedNode.path("cookie_data");
                            if (cookieData.isObject()) {
                                Iterator<Map.Entry<String, JsonNode>> fields = cookieData.fields();
                                Map<String, String> cookies = new LinkedHashMap<>();
                                while (fields.hasNext()) {
                                    JsonNode domainArray = fields.next().getValue();
                                    if (domainArray.isArray()) {
                                        for (JsonNode item : domainArray) {
                                            String domain = item.path("domain").asText();
                                            if (domain.endsWith("weread.qq.com")) {
                                                String name = item.path("name").asText();
                                                String val = item.path("value").asText();
                                                if (!name.isBlank() && val != null && !val.trim().isEmpty()) {
                                                    cookies.put(name, val);
                                                }
                                            }
                                        }
                                    }
                                }
                                if (isUsableWeReadCookie(cookies)) {
                                    StringBuilder cookieBuilder = new StringBuilder();
                                    cookies.forEach((name, val) ->
                                            cookieBuilder.append(name).append("=").append(val).append("; ")
                                    );
                                    return Mono.just(cookieBuilder.toString());
                                }
                            }
                        }
                        return Mono.error(new RuntimeException("数据格式错误：未找到 encrypted 字段或有效的微信读书 Cookie"));
                    } catch (Exception e) {
                        return Mono.error(new RuntimeException("解密失败: " + e.getMessage(), e));
                    }
                });
    }

    private boolean isUsableWeReadCookie(Map<String, String> cookies) {
        String wrVid = cookies.getOrDefault("wr_vid", "");
        String wrName = cookies.getOrDefault("wr_name", "");
        String wrSkey = cookies.getOrDefault("wr_skey", "");
        if (!wrVid.isBlank() && (!wrName.isBlank() || !wrSkey.isBlank())) {
            return true;
        }
        log.warn(
                "CookieCloud: 微信读书 Cookie 不完整，keys={}, hasWrVid={}, hasWrName={}, hasWrSkey={}",
                cookies.keySet(),
                !wrVid.isBlank(),
                !wrName.isBlank(),
                !wrSkey.isBlank()
        );
        return false;
    }

    private String decryptCryptoJS(String encryptedBase64, String uuid, String password) throws Exception {
        MessageDigest basicMd5 = MessageDigest.getInstance("MD5");
        byte[] preKeyHash = basicMd5.digest((uuid + "-" + password).getBytes("UTF-8"));
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : preKeyHash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        String theKey = hexString.toString().substring(0, 16);
        
        byte[] cipherData = Base64.getDecoder().decode(encryptedBase64);
        
        if (cipherData.length < 16 || !new String(cipherData, 0, 8, "UTF-8").equals("Salted__")) {
            throw new RuntimeException("非法的 AES 结构载荷（缺失 Salted__ 头）");
        }
        
        byte[] saltData = Arrays.copyOfRange(cipherData, 8, 16);
        byte[] encryptedData = Arrays.copyOfRange(cipherData, 16, cipherData.length);
        
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[][] keyAndIV = evpBytesToKey(32, 16, 1, saltData, theKey.getBytes("UTF-8"), md5);
        
        SecretKeySpec key = new SecretKeySpec(keyAndIV[0], "AES");
        IvParameterSpec iv = new IvParameterSpec(keyAndIV[1]);
        
        Cipher aesCBC = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCBC.init(Cipher.DECRYPT_MODE, key, iv);
        
        byte[] decryptedData = aesCBC.doFinal(encryptedData);
        return new String(decryptedData, "UTF-8");
    }

    private byte[][] evpBytesToKey(int keyLength, int ivLength, int iterations, byte[] salt, byte[] password, MessageDigest md) {
        int digestLength = md.getDigestLength();
        int requiredLength = (keyLength + ivLength + digestLength - 1) / digestLength * digestLength;
        byte[] generatedData = new byte[requiredLength];
        int generatedLength = 0;

        try {
            md.reset();
            byte[] digestData = new byte[0];
            while (generatedLength < keyLength + ivLength) {
                if (digestData.length > 0) {
                    md.update(digestData);
                }
                md.update(password);
                if (salt != null) {
                    md.update(salt, 0, 8);
                }
                digestData = md.digest();

                for (int i = 1; i < iterations; i++) {
                    md.reset();
                    md.update(digestData);
                    digestData = md.digest();
                }

                System.arraycopy(digestData, 0, generatedData, generatedLength, digestData.length);
                generatedLength += digestData.length;
            }

            byte[][] result = new byte[2][];
            result[0] = Arrays.copyOfRange(generatedData, 0, keyLength);
            result[1] = Arrays.copyOfRange(generatedData, keyLength, keyLength + ivLength);
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
