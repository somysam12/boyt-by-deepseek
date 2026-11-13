// IRequestPermissionsResult.aidl
package com.mundo.core.system.am;

interface IRequestPermissionsResult {
    boolean onResult(int requestCode,in String[] permissions,in int[] grantResults);
}