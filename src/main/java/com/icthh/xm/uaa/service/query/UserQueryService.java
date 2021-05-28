package com.icthh.xm.uaa.service.query;

import static java.util.Optional.ofNullable;

import com.icthh.xm.uaa.domain.User;
import com.icthh.xm.uaa.domain.UserLogin;
import com.icthh.xm.uaa.domain.UserLogin_;
import com.icthh.xm.uaa.domain.User_;
import com.icthh.xm.uaa.repository.UserRepository;
import com.icthh.xm.uaa.service.dto.UserDTO;
import com.icthh.xm.uaa.service.query.filter.SoftUserFilterQuery;
import com.icthh.xm.uaa.service.query.filter.StrictUserFilterQuery;
import io.github.jhipster.service.QueryService;
import io.github.jhipster.service.filter.Filter;
import io.github.jhipster.service.filter.StringFilter;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Expression;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService extends QueryService<User> {

    private final UserRepository userRepository;

    public Page<UserDTO> findAllUsersByStrictMatch(StrictUserFilterQuery filterQuery, Pageable pageable) {
        Specification<User> specification = createStrictSpecification(filterQuery);
        return userRepository.findAll(specification, pageable).map(UserDTO::new);
    }

    public Page<UserDTO> findAllUsersBySoftMatch(SoftUserFilterQuery query, Pageable pageable) {
        Specification<User> specification = createSoftSpecification(query);
        return userRepository.findAll(specification, pageable).map(UserDTO::new);
    }

    private Specification<User> createStrictSpecification(StrictUserFilterQuery filterQuery) {
        return createStrictSpecs(filterQuery)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .reduce(Specification.where(null), Specification::and);
    }

    private Specification<User> createSoftSpecification(SoftUserFilterQuery query) {
        return Stream.of(
            ofNullable(query.getRoleKey()).map(fn -> buildStringSpecification(fn, User_.roleKey)),
            ofNullable(query.getActivated()).map(fn -> buildSpecification(fn, User_.activated))
        )
            .filter(Optional::isPresent)
            .map(Optional::get)
            .reduce(Specification.where(
                getLoginSpecificationForSoft(query.getQuery())
                    .or(buildStringSpecification(query.getQuery(), User_.lastName))
                    .or(buildStringSpecification(query.getQuery(), User_.firstName))
            ), Specification::and);
    }

    private Stream<Optional<Specification<User>>> createStrictSpecs(StrictUserFilterQuery filterQuery) {
        return Stream.of(
            ofNullable(filterQuery.getLogin()).map(this::getLoginSpecificationForStrict),
            ofNullable(filterQuery.getLastName()).map(ln -> buildStringSpecification(ln, User_.lastName)),
            ofNullable(filterQuery.getFirstName()).map(fn -> buildStringSpecification(fn, User_.firstName)),
            ofNullable(filterQuery.getRoleKey()).map(fn -> buildStringSpecification(fn, User_.roleKey)),
            ofNullable(filterQuery.getActivated()).map(fn -> buildSpecification(fn, User_.activated))
        );
    }

    private Specification<User> getLoginSpecificationForStrict(StringFilter loginFilter) {
        Function<Root<User>, Join<User, UserLogin>> functionToEntity = userRoot -> (Join<User, UserLogin>) userRoot.fetch(User_.logins);
        Function<Join<User, UserLogin>, Expression<String>> entityToColumn = entity -> entity.get(UserLogin_.login);
        return buildSpecification(loginFilter, functionToEntity.andThen(entityToColumn));
    }

    private Specification<User> getLoginSpecificationForSoft(StringFilter loginFilter) {
        Function<Root<User>, Join<User, UserLogin>> functionToEntity = userRoot -> (Join<User, UserLogin>) userRoot.join(User_.logins);
        Function<Join<User, UserLogin>, Expression<String>> entityToColumn = entity -> entity.get(UserLogin_.login);
        return buildSpecificationDistinct(loginFilter, functionToEntity.andThen(entityToColumn));
    }

    protected Specification<User> buildSpecificationDistinct(StringFilter filter, Function<Root<User>, Expression<String>> metaclassFunction) {
        if (filter.getEquals() != null) {
            return equalsSpecificationDistinct(metaclassFunction, filter.getEquals());
        } else if (filter.getIn() != null) {
            return valueInDistinct(metaclassFunction, filter.getIn());
        } else if (filter.getContains() != null) {
            return likeUpperSpecificationDistinct(metaclassFunction, filter.getContains());
        } else if (filter.getDoesNotContain() != null) {
            return doesNotContainSpecificationDistinct(metaclassFunction, filter.getDoesNotContain());
        } else if (filter.getNotEquals() != null) {
            return notEqualsSpecificationDistinct(metaclassFunction, filter.getNotEquals());
        } else if (filter.getSpecified() != null) {
            return byFieldSpecifiedDistinct(metaclassFunction, filter.getSpecified());
        }
        return null;
    }

    protected <X> Specification<User> equalsSpecificationDistinct(Function<Root<User>, Expression<X>> metaclassFunction, X value) {
        return (root, query, builder) -> {
            final Predicate predicate = builder.equal(metaclassFunction.apply(root), value);
            query.distinct(true);
            return predicate;
        };
    }

    protected <X> Specification<User> notEqualsSpecificationDistinct(Function<Root<User>, Expression<X>> metaclassFunction, X value) {
        return (root, query, builder) -> {
            final Predicate predicate = builder.not(builder.equal(metaclassFunction.apply(root), value));
            query.distinct(true);
            return predicate;
        };
    }

    protected Specification<User> doesNotContainSpecificationDistinct(Function<Root<User>, Expression<String>> metaclassFunction, String value) {
        return (root, query, builder) -> {
            final Predicate predicate =  builder.not(builder.like(builder.upper(metaclassFunction.apply(root)), wrapLikeQuery(value)));
            query.distinct(true);
            return predicate;
        };
    }

    protected Specification<User> likeUpperSpecificationDistinct(Function<Root<User>, Expression<String>> metaclassFunction, String value) {
        return (root, query, builder) -> {
            final Predicate predicate = builder.like(builder.upper(metaclassFunction.apply(root)), wrapLikeQuery(value));
            query.distinct(true);
            return predicate;
        };
    }

    protected <X> Specification<User> byFieldSpecifiedDistinct(Function<Root<User>, Expression<X>> metaclassFunction, boolean specified) {
        return (root, query, builder) -> {
            final Predicate predicate = specified ?
                builder.isNotNull(metaclassFunction.apply(root)) :
                builder.isNull(metaclassFunction.apply(root));
            query.distinct(true);
            return predicate;
        };
    }

    protected <X> Specification<User> valueInDistinct(Function<Root<User>, Expression<X>> metaclassFunction, Collection<X> values) {
        return (root, query, builder) -> {
            CriteriaBuilder.In<X> predicate = builder.in(metaclassFunction.apply(root));
            for (X value : values) {
                predicate = predicate.value(value);
            }
            query.distinct(true);
            return predicate;
        };
    }
}
