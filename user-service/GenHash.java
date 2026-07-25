import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class GenHash {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        System.out.println("admin=" + encoder.encode("admin"));
        System.out.println("123=" + encoder.encode("123"));
        // verify existing hashes
        System.out.println("verify_admin=" + encoder.matches("admin", "$2a$10$4lmhGisufs299P.N968NMeE0pAqYTfJqeh/ocHTnjeSIUAdS/nrom"));
        System.out.println("verify_123=" + encoder.matches("123", "$2b$10$TgqIPS5ibdAU4G4rcclgL.V3XzT36FM56y34TmsrjrAUJT6YFOv7S"));
    }
}
