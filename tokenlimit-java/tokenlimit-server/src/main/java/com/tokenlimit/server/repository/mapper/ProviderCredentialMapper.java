package com.tokenlimit.server.repository.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenlimit.server.entity.ProviderCredential;
import org.apache.ibatis.annotations.Mapper;

/**
 * 供应商密钥凭证 Mapper.
 */
@Mapper
public interface ProviderCredentialMapper extends BaseMapper<ProviderCredential> {
}
