package com.icthh.xm.uaa.service.query.filter;

import io.github.jhipster.service.filter.BooleanFilter;
import io.github.jhipster.service.filter.StringFilter;
import lombok.Data;

import java.io.Serializable;

@Data
public abstract class AbstractUserFilterQuery implements Serializable {
    private StringFilter roleKey;
    private BooleanFilter activated;
}
