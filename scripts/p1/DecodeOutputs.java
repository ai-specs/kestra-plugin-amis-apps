import io.kestra.core.serializers.JacksonMapper;
public class DecodeOutputs {
    public static void main(String[] args) throws Exception {
        byte[] bytes = java.util.Base64.getDecoder().decode(new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s", ""));
        Object value = JacksonMapper.ofIon().readValue(bytes, Object.class);
        System.out.print(JacksonMapper.ofJson().writeValueAsString(value));
    }
}
