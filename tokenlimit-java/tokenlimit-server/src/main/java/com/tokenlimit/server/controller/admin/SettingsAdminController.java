package com.tokenlimit.server.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tokenlimit.common.api.Result;
import com.tokenlimit.server.entity.Setting;
import com.tokenlimit.server.repository.mapper.SettingMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：系统设置（键值对）.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
public class SettingsAdminController {

    private final SettingMapper settingMapper;

    public SettingsAdminController(SettingMapper settingMapper) {
        this.settingMapper = settingMapper;
    }

    /**
     * 获取全部设置（键值形式）.
     */
    @GetMapping
    public Result<Map<String, String>> list() {
        List<Setting> settings = settingMapper.selectList(null);
        Map<String, String> data = new LinkedHashMap<>();
        for (Setting s : settings) {
            data.put(s.getSettingKey(), s.getSettingValue());
        }
        return Result.success(data);
    }

    /**
     * 批量保存设置.
     */
    @PostMapping
    public Result<Map<String, String>> save(@RequestBody Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Setting setting = settingMapper.selectOne(new LambdaQueryWrapper<Setting>()
                    .eq(Setting::getSettingKey, entry.getKey()));
            if (setting == null) {
                setting = new Setting();
                setting.setSettingKey(entry.getKey());
                setting.setSettingValue(entry.getValue());
                settingMapper.insert(setting);
            } else {
                setting.setSettingValue(entry.getValue());
                settingMapper.updateById(setting);
            }
        }
        return Result.success(values);
    }
}
