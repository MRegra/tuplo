# A PuppetMaster experiment: write, freeze a replica, take, then recover.
client 0 examples/producer.tuplo
status
freeze 2
wait 100
unfreeze 2
status
crash 1
status
