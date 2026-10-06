# Preview 5 meadow launch failure on 26.3

The exact CI jar at source `97a3b046bde700e738daf3672abac51afca9fa54` passed packaging inspection but failed this native empty-inventory, 20-block meadow crafting-table case. The [artifact receipt](artifact.json) records its digest and removes production development outputs from the launch classpath. The [run](run.json) records failure, and the [console trace](console.txt) captures repeated jump rejections.

At the first ledge, the player was grounded at `(11.699999988,65,0.5)` with a clear body and full support. The edge from `(11,65,0)` to `(12,66,0)` failed its native swept-body proof. A numerical replay of the same seven-sample trajectory found that one conservative body union intersects the ledge. Centered starts pass that proof. The approach uses position thresholds without a settled-velocity gate; incoming momentum is the suspected trigger. The collision proof must remain intact.

The [route image](active-route.png) shows the actual active path, target and timer before the rejection. Preview 5 was kept unpublished after this failure. Its 24-job CI run was cancelled after the primary artifacts were preserved, so it does not establish all-version build completion.
