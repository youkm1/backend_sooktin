package com.sooktin.backend.global;

import com.sooktin.backend.domain.CareerCard;
import com.sooktin.backend.domain.Experience;
import com.sooktin.backend.domain.User;
import com.sooktin.backend.domain.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 성능 측정용 더미 데이터 생성기.
 *
 * 커리어카드가 2건뿐인 상태에서는 캐시를 켜든 끄든, N+1 이 있든 없든 전부 1ms 라
 * 병목이 드러나지 않는다. 측정에 의미가 생기려면 실제 서비스 규모(재학생 수천 명)의
 * 데이터가 필요하고, skills / experiences 컬렉션까지 채워야 EAGER @ElementCollection
 * 3개로 인한 N+1 이 재현된다.
 *
 * local 프로필에서 sooktin.seed.enabled=true 일 때만 동작한다.
 */
@Slf4j
@Component
@Profile("local")
@ConditionalOnProperty(name = "sooktin.seed.enabled", havingValue = "true")
public class DevDataSeeder implements ApplicationRunner {

    /** 시딩된 계정의 공통 비밀번호. k6 스크립트의 SEED_PASSWORD 와 반드시 같아야 한다. */
    public static final String SEED_PASSWORD = "seedPassw0rd!";
    private static final String EMAIL_DOMAIN = "@sookmyung.ac.kr";

    private static final List<String> MAJORS = List.of(
            "컴퓨터과학", "데이터사이언스", "소프트웨어학", "경영학", "시각영상디자인",
            "화공생명공학", "통계학", "문헌정보학", "미디어학", "법학");
    private static final List<String> DEPARTMENTS = List.of(
            "IT부서", "마케팅팀", "인사팀", "디자인팀", "연구소", "전략기획팀");
    private static final List<String> JOBS = List.of(
            "백엔드", "프론트엔드", "인프라", "데이터엔지니어", "PM", "디자이너", "QA");
    private static final List<String> SKILLS = List.of(
            "Java", "Spring", "Python", "AWS", "Docker", "Kubernetes",
            "React", "TypeScript", "MySQL", "Redis", "Kafka", "Go");
    private static final List<String> COMPANIES = List.of(
            "네이버", "카카오", "라인", "쿠팡", "배달의민족", "토스",
            "당근마켓", "삼성전자", "LG CNS", "SK텔레콤");
    private static final List<String> STATUSES = List.of("재학", "휴학", "졸업");

    @PersistenceContext
    private EntityManager em;

    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${sooktin.seed.count:5000}")
    private int count;

    /** flush/clear 주기. hibernate.jdbc.batch_size 와 맞춰두면 배치 인서트가 제대로 묶인다. */
    @Value("${sooktin.seed.batch-size:100}")
    private int batchSize;

    public DevDataSeeder(BCryptPasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long existing = em.createQuery("select count(c) from CareerCard c", Long.class).getSingleResult();
        if (existing >= count) {
            log.info("[seed] 커리어카드가 이미 {}건 있어 시딩을 건너뜁니다.", existing);
            return;
        }

        // bcrypt 는 한 건당 100ms 수준이라 5000번 돌리면 8분 걸린다.
        // 시딩 계정은 모두 같은 비밀번호이므로 해시를 한 번만 계산해 재사용한다.
        String sharedHash = passwordEncoder.encode(SEED_PASSWORD);

        Random random = new Random(42); // 재현 가능한 데이터셋
        long started = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            String suffix = String.format("%05d", i);

            User user = User.builder()
                    .email("seed" + suffix + EMAIL_DOMAIN)
                    .nickname("seed" + suffix)      // @Size(2,10) 제약을 지켜야 한다
                    .password(sharedHash)
                    .roles(Set.of(UserRole.USER))
                    .build();
            em.persist(user);

            em.persist(buildCard(user, random));

            if (i % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();
        em.clear();

        log.info("[seed] 커리어카드 {}건 생성 완료 ({}ms). 로그인 계정 예시: seed00000{} / {}",
                count, System.currentTimeMillis() - started, EMAIL_DOMAIN, SEED_PASSWORD);
    }

    private CareerCard buildCard(User user, Random random) {
        CareerCard card = new CareerCard();
        card.setUser(user);
        card.setMajor(pick(MAJORS, random));
        card.setDepartment(pick(DEPARTMENTS, random));
        card.setJob(pick(JOBS, random));
        card.setStudent_status(pick(STATUSES, random));
        card.setGrade(1 + random.nextInt(4));
        card.setStudent_num(String.valueOf(19 + random.nextInt(6)));

        // 컬렉션을 채워야 EAGER @ElementCollection 로 인한 N+1 이 실제로 재현된다
        List<String> skills = new ArrayList<>();
        for (int s = 0; s < 3 + random.nextInt(3); s++) {
            String skill = pick(SKILLS, random);
            if (!skills.contains(skill)) {
                skills.add(skill);
            }
        }
        card.setSkills(skills);

        List<Experience> experiences = new ArrayList<>();
        for (int e = 0; e < 1 + random.nextInt(3); e++) {
            int startYear = 2018 + random.nextInt(6);
            experiences.add(new Experience(pick(COMPANIES, random), startYear + "-" + (startYear + 1)));
        }
        card.setExperiences(experiences);

        return card;
    }

    private String pick(List<String> source, Random random) {
        return source.get(random.nextInt(source.size()));
    }
}
