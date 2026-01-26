# Only one instance of CLion can be run at a time.
That's because the last running CLion was not closed properly. 
To terminate the previous instance, run this:
```bash
ps aux | grep com.intellij.idea.Main | grep -v grep | awk '{print $2}' | xargs sudo kill -9
```

# Not cancellable waiting?
If log in the console showing this:
```txt
2026-01-26 20:38:54,174 [   2100]   WARN - c.j.cidr - Not cancellable waiting?
java.lang.Exception: Not cancellable waiting?
	at com.jetbrains.cidr.util.CidrConcurrentUtilsKt.prepareIndicatorToBeCancelledByWA(CidrConcurrentUtils.kt:182)
	at com.jetbrains.cidr.util.CidrConcurrentUtilsKt.waitImpl(CidrConcurrentUtils.kt:67)
	at com.jetbrains.cidr.util.CidrConcurrentUtilsKt.waitCancelAndWriteActionAware(CidrConcurrentUtils.kt:57)
	at com.jetbrains.cidr.util.CidrConcurrentUtilsKt.waitCancelAndWriteActionAware(CidrConcurrentUtils.kt:46)
	at com.jetbrains.cidr.lang.daemon.clang.ExternalResolveUtils.findInParallel(ExternalResolveUtils.java:226)
	at com.jetbrains.cidr.lang.daemon.clang.ExternalResolveUtils.findCombined(ExternalResolveUtils.java:163)
	at com.jetbrains.cidr.lang.psi.impl.OCReferenceElementImpl.resolve(OCReferenceElementImpl.java:134)
	at io.github.xyzboom.ssreducer.cpp.GroupElements$ProcessVisitor.visitElement(GroupElements.kt:45)
	at io.github.xyzboom.ssreducer.cpp.OCReverseDFSVisitor.visitElement(OCReverseDFSVisitor.kt:12)
	at io.github.xyzboom.ssreducer.cpp.GroupElements$ProcessVisitor.visitElement(GroupElements.kt:39)
	....
```
Do not worry, just ignore it. It is a warning from CLion's internal code analysis engine and does not affect the functionality of the SSReducer plugin.
Some other waring for the internal CLion code analysis engine can also be ignored safely.

# The results of each run are inconsistent?
A program that reaches a local minimum may have different reduction paths, and each locally minimal program may not be 
globally minimal. This is because ddmin only guarantees minimality along the current path, but does not guarantee global
minimality. Due to the nature of multithreading, some successful intermediate reductions may be discovered earlier or 
later than in previous runs, which can lead to variations in results across different executions.