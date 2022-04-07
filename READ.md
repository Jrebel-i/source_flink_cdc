任务执行调度 
SchedulerBase的startScheduling()方法进入
||
调用SourceCoordinator#start()方法
||
调用MySqlSource.createEnumerator() 创建 MySqlSourceEnumerator
MySqlSourceEnumerator 并调用start方法，做一些初始化工作


调用org.apache.flink.runtime.taskmanager.Task中的invokable.restore()
最终调用SourceOperator的open()方法