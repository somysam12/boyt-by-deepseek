package com.mundo.core.gms;

import android.os.Bundle;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain holder for a Google sign-in result, plus a writer that produces the exact
 * SafeParcel layout of com.google.android.gms.auth.api.signin.GoogleSignInAccount
 * (verified against microG play-services-base GoogleSignInAccount.java):
 *
 *   1 versionCode (=3)   2 id               3 idToken     4 email
 *   5 displayName        6 photoUrl (Uri)   7 serverAuthCode
 *   8 expirationTimeSecs (long)             9 obfuscatedIdentifier
 *   10 grantedScopes (List<Scope>)          11 givenName  12 familyName
 *
 * The holder travels between processes as a Bundle; the guest side turns the
 * SafeParcel bytes into the app's real Google object via its own CREATOR
 * (see ForeignParcelBundle / FakeSignInService), so no Google classes are needed in MundoCore.
 */
public class GoogleSignInAccount implements SafeParcelHelper.Writable {

    public String id;
    public String idToken;
    public String email;
    public String displayName;
    public String photoUrl;
    public String serverAuthCode;
    public long expirationTimeSecs;
    public String obfuscatedIdentifier;
    public List<String> grantedScopes = new ArrayList<>();
    public String givenName;
    public String familyName;

    @Override
    public void writeSafeParcel(Parcel dest) {
        int s = SafeParcelHelper.beginObject(dest);
        SafeParcelHelper.writeInt(dest, 1, 3);
        SafeParcelHelper.writeString(dest, 2, id);
        SafeParcelHelper.writeString(dest, 3, idToken);
        SafeParcelHelper.writeString(dest, 4, email);
        SafeParcelHelper.writeString(dest, 5, displayName);
        if (photoUrl != null) {
            writeUri(dest, 6, photoUrl);
        }
        SafeParcelHelper.writeString(dest, 7, serverAuthCode);
        SafeParcelHelper.writeLong(dest, 8, expirationTimeSecs);
        SafeParcelHelper.writeString(dest, 9, obfuscatedIdentifier);
        List<SafeParcelHelper.Scope> scopes = new ArrayList<>();
        for (String sc : grantedScopes) scopes.add(new SafeParcelHelper.Scope(sc));
        SafeParcelHelper.writeTypedList(dest, 10, scopes);
        SafeParcelHelper.writeString(dest, 11, givenName);
        SafeParcelHelper.writeString(dest, 12, familyName);
        SafeParcelHelper.finishObject(dest, s);
    }

    /** Uri is written via writeParcelable(id, uri) = sized block containing Uri.writeToParcel. */
    private static void writeUri(Parcel dest, int id, String uri) {
        dest.writeInt(0xFFFF0000 | id);
        dest.writeInt(0);
        int start = dest.dataPosition();
        android.net.Uri.parse(uri).writeToParcel(dest, 0);
        int end = dest.dataPosition();
        dest.setDataPosition(start - 4);
        dest.writeInt(end - start);
        dest.setDataPosition(end);
    }

    /** SignInAccount (play-services-auth): 4 email, 7 googleSignInAccount, 8 userId. */
    public void writeSignInAccount(Parcel dest) {
        int s = SafeParcelHelper.beginObject(dest);
        SafeParcelHelper.writeString(dest, 4, email != null ? email : "");
        dest.writeInt(0xFFFF0000 | 7);
        dest.writeInt(0);
        int start = dest.dataPosition();
        writeSafeParcel(dest);
        int end = dest.dataPosition();
        dest.setDataPosition(start - 4);
        dest.writeInt(end - start);
        dest.setDataPosition(end);
        SafeParcelHelper.writeString(dest, 8, id != null ? id : "");
        SafeParcelHelper.finishObject(dest, s);
    }

    // ---- Bundle transport ---------------------------------------------------

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString("id", id);
        b.putString("idToken", idToken);
        b.putString("email", email);
        b.putString("displayName", displayName);
        b.putString("photoUrl", photoUrl);
        b.putString("serverAuthCode", serverAuthCode);
        b.putLong("expirationTimeSecs", expirationTimeSecs);
        b.putString("obfuscatedIdentifier", obfuscatedIdentifier);
        b.putStringArrayList("grantedScopes", new ArrayList<>(grantedScopes));
        b.putString("givenName", givenName);
        b.putString("familyName", familyName);
        return b;
    }

    public static GoogleSignInAccount fromBundle(Bundle b) {
        if (b == null) return null;
        GoogleSignInAccount a = new GoogleSignInAccount();
        a.id = b.getString("id");
        a.idToken = b.getString("idToken");
        a.email = b.getString("email");
        a.displayName = b.getString("displayName");
        a.photoUrl = b.getString("photoUrl");
        a.serverAuthCode = b.getString("serverAuthCode");
        a.expirationTimeSecs = b.getLong("expirationTimeSecs");
        a.obfuscatedIdentifier = b.getString("obfuscatedIdentifier");
        ArrayList<String> sc = b.getStringArrayList("grantedScopes");
        if (sc != null) a.grantedScopes = sc;
        a.givenName = b.getString("givenName");
        a.familyName = b.getString("familyName");
        return a;
    }

    @Override
    public String toString() {
        return "GoogleSignInAccount{email=" + email + ", id=" + id
                + ", idToken=" + (idToken != null) + ", serverAuthCode=" + (serverAuthCode != null)
                + ", scopes=" + grantedScopes + "}";
    }
}
