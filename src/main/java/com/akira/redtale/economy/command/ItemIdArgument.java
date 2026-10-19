package com.akira.redtale.economy.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 物品 id 参数：允许 {@code minecraft:oak_log} 这种带冒号的写法。
 *
 * <p>Brigadier 自带的 {@link com.mojang.brigadier.arguments.StringArgumentType#word()}
 * 只接受 {@code [a-zA-Z0-9_+-.]}，遇到冒号会在 {@code minecraft:} 处报
 * 「参数后应有空格分隔」——这就是 {@code /shop admin add test minecraft:oak_log 1 1} 失败的原因。
 *
 * <p>返回 String（未写的命名空间补 {@code minecraft:}），所以调用方仍可用
 * {@code StringArgumentType.getString(ctx, "itemId")}（它内部就是 {@code getArgument(name, String.class)}）。
 */
public final class ItemIdArgument implements ArgumentType<String> {

	private static final List<String> EXAMPLES = List.of("minecraft:oak_log", "coc:chair", "redtale_economy:makeup_ticket");

	public static ItemIdArgument itemId() {
		return new ItemIdArgument();
	}

	@Override
	public String parse(StringReader reader) throws CommandSyntaxException {
		int start = reader.getCursor();
		while (reader.canRead() && !Character.isWhitespace(reader.peek())) {
			reader.skip();
		}
		String raw = reader.getString().substring(start, reader.getCursor());
		if (raw.isEmpty()) {
			throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherExpectedArgumentSeparator()
					.createWithContext(reader);
		}
		// 容错：只写 oak_log 也行
		return raw.indexOf(':') >= 0 ? raw : "minecraft:" + raw;
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		String remain = builder.getRemaining().toLowerCase();
		for (Identifier id : Registries.ITEM.getIds()) {
			String s = id.toString();
			if (s.toLowerCase().startsWith(remain) || s.toLowerCase().contains(remain)) {
				builder.suggest(s);
			}
		}
		// 方块类物品优先排在前面
		return builder.buildFuture();
	}

	@Override
	public Collection<String> getExamples() {
		return EXAMPLES;
	}
}
