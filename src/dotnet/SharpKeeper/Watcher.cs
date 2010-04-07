using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace SharpKeeper
{
    public interface Watcher
    {      
        void process(WatchedEvent @event);
    }
}
