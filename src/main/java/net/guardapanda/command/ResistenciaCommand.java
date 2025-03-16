package net.guardapanda.command;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerPlayer;

@Mod("guardapanda")
public class ResistenciaCommand {

    public static final String MODID = "guardapanda";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    public ResistenciaCommand() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Resistencia Balancer iniciado!");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Resistencia Balancer encerrado!");
    }

    @SubscribeEvent
    public void onLivingDamage(LivingDamageEvent event) {
        LivingEntity entity = event.getEntity();
        DamageSource source = event.getSource();
        float amount = event.getAmount();

        // Verifica se a entidade tem o efeito de resistência e se o dano não ignora a invulnerabilidade
        if (entity.hasEffect(MobEffects.DAMAGE_RESISTANCE)) {
            int resistanceLevel = entity.getEffect(MobEffects.DAMAGE_RESISTANCE).getAmplifier() + 1;
            float reducedAmount;

            if (ResistanceBalancerConfig.enable_custom_formula) {
                String formula = ResistanceBalancerConfig.custom_formula.replace("RESISTANCE", String.valueOf(resistanceLevel));
                float reductionFactor = (float) FormulaParser.evaluateFormula(formula);
                reducedAmount = amount * (1.0F - reductionFactor);
            } else {
                float fallbackReduction = resistanceLevel * ResistanceBalancerConfig.percentile_reduction_fallback;
                reducedAmount = amount * (1.0F - fallbackReduction);
            }

            float resistedDamage = amount - reducedAmount;
            if (resistedDamage > 0.0F && resistedDamage < Float.MAX_VALUE) {
                if (entity instanceof ServerPlayer) {
                    ((ServerPlayer) entity).causeFoodExhaustion(resistedDamage * 10.0F);
                } else if (source.getEntity() instanceof ServerPlayer) {
                    ((ServerPlayer) source.getEntity()).causeFoodExhaustion(resistedDamage * 10.0F);
                }
            }

            event.setAmount(Math.max(reducedAmount, 0.0F));
        }
    }

    public static class ResistanceBalancerConfig {
        public static boolean enable_custom_formula = false;
        public static String custom_formula = "min(RESISTANCE / 4.0, 1.0) * 0.20";
        public static float percentile_reduction_fallback = 0.05F;
    }

    public static class FormulaParser {
        public static double evaluateFormula(String formula) {
            try {
                return new Object() {
                    int pos = -1, ch;

                    void nextChar() {
                        ch = (++pos < formula.length()) ? formula.charAt(pos) : -1;
                    }

                    boolean eat(int charToEat) {
                        while (ch == ' ') nextChar();
                        if (ch == charToEat) {
                            nextChar();
                            return true;
                        }
                        return false;
                    }

                    double parse() {
                        nextChar();
                        double x = parseExpression();
                        if (pos < formula.length()) throw new RuntimeException("Unexpected: " + (char) ch);
                        return x;
                    }

                    double parseExpression() {
                        double x = parseTerm();
                        while (true) {
                            if (eat('+')) x += parseTerm();
                            else if (eat('-')) x -= parseTerm();
                            else return x;
                        }
                    }

                    double parseTerm() {
                        double x = parseFactor();
                        while (true) {
                            if (eat('*')) x *= parseFactor();
                            else if (eat('/')) x /= parseFactor();
                            else return x;
                        }
                    }

                    double parseFactor() {
                        if (eat('+')) return parseFactor();
                        if (eat('-')) return -parseFactor();

                        double x;
                        int startPos = pos;
                        if (eat('(')) {
                            x = parseExpression();
                            eat(')');
                        } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                            while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                            x = Double.parseDouble(formula.substring(startPos, pos));
                        } else if (ch >= 'a' && ch <= 'z') {
                            while (ch >= 'a' && ch <= 'z') nextChar();
                            String func = formula.substring(startPos, pos);
                            x = parseFactor();
                            switch (func) {
                                case "sqrt": x = Math.sqrt(x); break;
                                case "sin": x = Math.sin(Math.toRadians(x)); break;
                                case "cos": x = Math.cos(Math.toRadians(x)); break;
                                case "tan": x = Math.tan(Math.toRadians(x)); break;
                                default: throw new RuntimeException("Unknown function: " + func);
                            }
                        } else {
                            throw new RuntimeException("Unexpected: " + (char) ch);
                        }

                        if (eat('^')) x = Math.pow(x, parseFactor());
                        return x;
                    }
                }.parse();
            } catch (NumberFormatException e) {
                LOGGER.error("Erro ao analisar a fórmula: " + formula, e);
                return 0.0;
            }
        }
    }
}