任务执行调度 
SchedulerBase的startScheduling()方法进入
||
调用SourceCoordinator#start()方法
||
调用MySqlSource.createEnumerator() 创建 MySqlSourceEnumerator
MySqlSourceEnumerator 并调用start方法，做一些初始化工作


调用org.apache.flink.runtime.taskmanager.Task中的invokable.restore()
最终调用SourceOperator的open()方法

可查看：https://my.oschina.net/u/4471526/blog/5280473
https://www.jianshu.com/p/b3890d7b521a

Source===》MysqlSource
包含创建SourceReader，SplitEnumerator和对应Serializers的工厂方法

SourceReader===》MySqlSourceReader
读取SplitEnumerator分配的source split

SplitEnumerator===》HybridSourceSplitEnumerator
1,实时发现可供SourceReader读取的split    2,为SourceReader分配split

SourceCoordinator
使用event loop线程模型和Flink runtime交互，确保所有的状态操作在event loop线程（单线程池）中
start方法 创建出enumerator并且调用enumerator的start方法
handleEventFromOperator方法用来接收operator发来的事件。然后做出响应。
SourceOperator 通过OperatorEventGateway 和 SourceCoordinator 进行交互

SplitAssigner===》MySqlHybridSplitAssigner
负责决定哪个split需要被哪个节点处理。它确定了split处理的顺序和位置


Enumerator接收到分片请求后，就会调用SplitAssigner来确定分片需要到哪一个节点



