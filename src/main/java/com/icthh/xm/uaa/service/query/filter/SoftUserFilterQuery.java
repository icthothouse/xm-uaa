package com.icthh.xm.uaa.service.query.filter;

import io.github.jhipster.service.filter.StringFilter;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = true)
public class SoftUserFilterQuery extends AbstractUserFilterQuery implements Serializable {
    private StringFilter query;
}
