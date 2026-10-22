package com.akira.redtale.economy.module;

import com.akira.redtale.economy.module.impl.BountyModule;
import com.akira.redtale.economy.module.impl.TaskModule;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 模块开关必须贴在「发钱」的唯一实现上（A 第 N+77 轮）。
 *
 * <p>为什么需要这组用例：T51 模块热开关的实现是「关掉 ⇒ 反射摘掉根命令」。
 * 但命令面只是入口之一，而 Fabric <b>没有反注册 API</b>：模块只要加载过，
 * 它的 C2S 收包点和静态发奖方法就一直存在。所以「能不能发钱」这个判断
 * 必须写在发钱那一刻旁边，而不能只信任调用方。
 *
 * <p>用「模块未启用」这个默认态就能验证：{@code ModuleManager.effective} 为空
 * ⇒ {@code isEnabled(id)=false} ⇒ 发奖方法必须在<b>读盘之前</b>返回 -1。
 * 这条同时钉住了另一件事：门禁一旦被挪到 load() 之后，这两个用例会因为
 * 在 headless 环境下读不到数据目录而炸 —— 位置错会立刻暴露。
 */
class ModuleGateTest {

	@Test
	void taskClaimRefusedWhenModuleOff() {
		assertFalse(TaskModule.enabled(), "测试环境里没有任何模块被 bootstrap，tasks 必须是关闭态");
		assertEquals(-1L, TaskModule.claimAndSave(UUID.randomUUID(), 0));
	}

	@Test
	void bountyClaimRefusedWhenModuleOff() {
		assertFalse(BountyModule.enabled(), "同上：bounty 必须是关闭态");
		assertEquals(-1L, BountyModule.claim(UUID.randomUUID(), 0));
	}

	@Test
	void modulesFacadeAgreesWithManager() {
		// 门面与 Manager 同一个口径（shop/warehouse/mail 的收包守卫走的就是 Modules.enabled）
		assertFalse(Modules.enabled("shop"));
		assertFalse(Modules.enabled("warehouse"));
		assertFalse(Modules.enabled("mail"));
		assertFalse(Modules.enabled("不存在的模块"), "未知 id 一律视为关闭，调用方不用自己兜底");
	}
}
