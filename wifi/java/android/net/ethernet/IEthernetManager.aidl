
package android.net.ethernet;

interface IEthernetManager
{
    int getEthernetIfaceState();

    int getEthernetCarrierState();
	
    int getEthernetConnectState();

    boolean setEthernetEnabled(boolean enable);
    
    String getEthernetIfaceName();
}

