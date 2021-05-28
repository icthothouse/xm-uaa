package com.icthh.xm.uaa.service.query.filter;

import io.github.jhipster.service.filter.StringFilter;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@Data
@EqualsAndHashCode(callSuper = true)
public class StrictUserFilterQuery extends AbstractUserFilterQuery implements Serializable {
    private StringFilter firstName;
    private StringFilter lastName;
    private StringFilter login;
}
