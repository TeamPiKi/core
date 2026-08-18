package com.depromeet.piki.product.routing

import org.springframework.data.jpa.repository.JpaRepository

interface DomainAccessPolicyJpaRepository : JpaRepository<DomainAccessPolicyEntity, String>
