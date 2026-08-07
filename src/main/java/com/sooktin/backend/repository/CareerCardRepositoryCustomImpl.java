package com.sooktin.backend.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sooktin.backend.domain.CareerCard;
import com.sooktin.backend.domain.QCareerCard;
import com.sooktin.backend.domain.QExperience;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class CareerCardRepositoryCustomImpl implements CareerCardRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    /*
     * experiences, skills 조건은 cc.experiences.any() 로 쓰면 카드 한 건마다
     * careercards 를 다시 스캔하는 상관 서브쿼리가 만들어진다.
     * 카드 수가 늘어나면 그만큼 곱으로 느려지므로 명시적 조인 + distinct 로 한 번만 훑는다.
     */
    @Override
    public Page<CareerCard> searchCareerCards(String keyword, Pageable pageable) {
        QCareerCard cc = QCareerCard.careerCard;
        QExperience experience = new QExperience("experience");
        StringPath skill = Expressions.stringPath("skill");

        BooleanBuilder builder = new BooleanBuilder();

        if (keyword != null && !keyword.isEmpty()) {
            String pattern = "%" + keyword + "%";
            builder.andAnyOf(
                    cc.major.like(pattern),
                    cc.department.like(pattern),
                    cc.job.like(pattern),
                    experience.company.like(pattern),
                    skill.like(pattern)
            );
        }
        List<CareerCard> content = queryFactory
                .selectFrom(cc)
                .distinct()
                .leftJoin(cc.experiences, experience)
                .leftJoin(cc.skills, skill)
                .where(builder)
                .orderBy(cc.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(cc.countDistinct())
                .from(cc)
                .leftJoin(cc.experiences, experience)
                .leftJoin(cc.skills, skill)
                .where(builder)
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }

    @Override
    public Page<CareerCard> searchCareerCardsWithOrCondition(String keyword, Pageable pageable) {
        QCareerCard cc = QCareerCard.careerCard;
        QExperience experience = new QExperience("experience");
        StringPath skill = Expressions.stringPath("skill");

        BooleanBuilder builder = new BooleanBuilder();

        if (keyword != null && !keyword.trim().isEmpty()) {
            String[] keywords = keyword.trim().split("\\s+");
            BooleanBuilder orBuilder = new BooleanBuilder();

            for (String singleKeyword : keywords) {
                BooleanBuilder condition = new BooleanBuilder();
                boolean isExactMatch = singleKeyword.startsWith("\"") && singleKeyword.endsWith("\"");
                singleKeyword = singleKeyword.replaceAll("^\"|\"$", "");

                if (isExactMatch) {
                    condition.or(cc.major.equalsIgnoreCase(singleKeyword))
                            .or(cc.department.equalsIgnoreCase(singleKeyword))
                            .or(cc.job.equalsIgnoreCase(singleKeyword))
                            .or(experience.company.equalsIgnoreCase(singleKeyword))
                            .or(skill.equalsIgnoreCase(singleKeyword));
                } else {
                    String pattern = "%" + singleKeyword + "%";
                    condition.or(cc.major.likeIgnoreCase(pattern))
                            .or(cc.department.likeIgnoreCase(pattern))
                            .or(cc.job.likeIgnoreCase(pattern))
                            .or(experience.company.likeIgnoreCase(pattern))
                            .or(skill.likeIgnoreCase(pattern));
                }

                orBuilder.or(condition);
            }

            builder.and(orBuilder);
        }

        List<CareerCard> content = queryFactory
                .selectFrom(cc)
                .distinct()
                .leftJoin(cc.experiences, experience)
                .leftJoin(cc.skills, skill)
                .where(builder)
                .orderBy(cc.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(cc.countDistinct())
                .from(cc)
                .leftJoin(cc.experiences, experience)
                .leftJoin(cc.skills, skill)
                .where(builder)
                .fetchOne();

        return new PageImpl<>(content, pageable, total != null ? total : 0L);
    }
}
