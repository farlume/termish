package dev.termish.util

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyleMedium

/** iOS：中等强度冲击反馈。实例全局持有（创建有成本，重复创建会丢反馈）。 */
private val generator = UIImpactFeedbackGenerator()

actual fun hapticTick() {
    generator.impactOccurredWithIntensity(0.8)
}
